package com.realityse.ravensight.core

/**
 * One queued analytics event. [data] values may be strings, numbers, booleans,
 * nulls, nested maps or lists; the transport layer serializes them to JSON.
 */
class RavensightEvent(
    val name: String,
    val data: Map<String, Any?>,
    /** Unix seconds, captured when the event happened. */
    val timestampSec: Long,
)

/** Tuning knobs for [RavensightCore]. The defaults match the live API contract. */
class RavensightCoreConfig(
    /** Offline queue cap. Oldest events are dropped first past this. */
    val maxQueueSize: Int = DEFAULT_QUEUE_CAP,
    /** Server hard limit on POST /track/batch. */
    val maxBatchSize: Int = MAX_BATCH_SIZE,
    /** First retry delay when the server gives no Retry-After. */
    val initialRetryMs: Long = DEFAULT_RETRY_MS,
    /** Ceiling for the exponential backoff. */
    val maxBackoffMs: Long = MAX_BACKOFF_MS,
    /** A session this close to expiry is treated as already expired. */
    val sessionExpirySkewMs: Long = 5_000L,
) {
    companion object {
        const val MAX_BATCH_SIZE: Int = 50
        const val DEFAULT_QUEUE_CAP: Int = 500
        const val DEFAULT_RETRY_MS: Long = 10_000L
        const val MAX_BACKOFF_MS: Long = 300_000L
    }
}

/** What the transport layer should do next. Returned by [RavensightCore.nextAction]. */
sealed class RavensightAction {
    /** Nothing to do right now. */
    object Idle : RavensightAction()

    /** GET /settings with the X-API-Key header, then call [RavensightCore.onSettingsResult]. */
    object CheckSettings : RavensightAction()

    /** POST /session with the X-API-Key header, then call [RavensightCore.onSessionResult]. */
    object OpenSession : RavensightAction()

    /**
     * POST /track/batch with the X-Session-Token header, then call
     * [RavensightCore.onBatchResult]. The events stay in the queue until the
     * server accepts them, so a failure loses nothing.
     */
    class SendBatch(val events: List<RavensightEvent>) : RavensightAction()

    /** Backoff or rate limit window. Do not touch the network before [atMs]. */
    class WaitUntil(val atMs: Long) : RavensightAction()
}

/** Result of feeding a session response into the core. */
sealed class SessionOutcome {
    object Ready : SessionOutcome()
    class RateLimited(val reason: String) : SessionOutcome()
    class Failed(val reason: String) : SessionOutcome()
}

/** Result of feeding a batch response into the core. */
sealed class BatchOutcome {
    /** The server accepted [count] events; they left the queue. */
    class Flushed(val count: Int) : BatchOutcome()

    /** 401: the session was invalidated, the batch stays queued for re-send. */
    object SessionExpired : BatchOutcome()

    /** 429: the queue is held until the retry window passes. */
    class RateLimited(val reason: String) : BatchOutcome()

    /** 400 on a multi-event batch: the next batch is halved to [newLimit]. */
    class BatchSplit(val newLimit: Int) : BatchOutcome()

    /** 400 on a single event: that event was dropped so it cannot block the queue. */
    object EventRejected : BatchOutcome()

    /** Anything else: backoff scheduled, the batch stays queued. */
    class Failed(val reason: String) : BatchOutcome()
}

/** Counters for diagnostics. Dropped counts both queue overflow and 400 rejections. */
class RavensightStats(
    val queued: Int,
    val sent: Long,
    val dropped: Long,
    val flushes: Long,
    val sessions: Long,
)

/**
 * The whole Ravensight delivery protocol as a pure state machine: the event
 * queue, batching in fifties, the server kill switch, session lifecycle,
 * re-authentication on 401, Retry-After on 429, exponential backoff from 10s
 * to a 5 minute ceiling, and oversize splitting on 400.
 *
 * This class performs no I/O and imports nothing from Android. A driver loop
 * (the Ravensight class in the Android module, or a unit test) repeatedly asks
 * [nextAction], performs the described request, and reports the response back
 * through the matching onXxxResult method. The clock is injected so tests can
 * step time.
 *
 * Not internally synchronized: all calls must come from the single thread that
 * owns the queue.
 */
class RavensightCore(
    private val config: RavensightCoreConfig = RavensightCoreConfig(),
    private val nowMs: () -> Long,
) {
    private val queue = ArrayDeque<RavensightEvent>()

    private var settingsChecked = false
    private var enabledLocally = true

    /** Server side kill switch from GET /settings. Assumed on until told otherwise. */
    var trackingEnabled: Boolean = true
        private set

    var sessionToken: String? = null
        private set

    /** Unix milliseconds. */
    var sessionExpiresAtMs: Long = 0L
        private set

    private var wantSession = false
    private var backoffMs = config.initialRetryMs
    private var nextAttemptAtMs = 0L
    private var batchLimit = config.maxBatchSize
    private var consecutiveAuthFailures = 0
    private var inFlightBatchSize = 0

    private var sent = 0L
    private var dropped = 0L
    private var flushes = 0L
    private var sessions = 0L

    // --- State queries -----------------------------------------------------

    /** True unless disabled locally or by the server kill switch. */
    val isActive: Boolean
        get() = enabledLocally && trackingEnabled

    val queueSize: Int
        get() = queue.size

    val stats: RavensightStats
        get() = RavensightStats(queue.size, sent, dropped, flushes, sessions)

    /** Milliseconds until the next allowed network attempt, 0 when clear. */
    val retryInMs: Long
        get() = (nextAttemptAtMs - nowMs()).coerceAtLeast(0L)

    fun isSessionValid(): Boolean {
        val token = sessionToken ?: return false
        return token.isNotEmpty() && nowMs() < sessionExpiresAtMs - config.sessionExpirySkewMs
    }

    // --- Local opt in and opt out ------------------------------------------

    /**
     * Local privacy toggle. Disabling discards anything still queued so an
     * opt-out does not leave player data sitting in memory.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled == enabledLocally) return
        enabledLocally = enabled
        if (!enabled) clearQueue()
    }

    // --- Tracking ----------------------------------------------------------

    /**
     * Queues an event, dropping the oldest queued event first once the queue
     * cap is reached so the newest always survive. Safe to call before the
     * settings check has answered. Returns false when tracking is off.
     */
    fun track(name: String, data: Map<String, Any?> = emptyMap()): Boolean {
        require(name.isNotEmpty()) { "event name is required" }
        if (!isActive) return false

        while (queue.size >= config.maxQueueSize) {
            queue.removeFirst()
            dropped++
        }
        queue.addLast(RavensightEvent(name, data, nowMs() / 1000L))
        return true
    }

    /**
     * Ask for a session to be opened even with an empty queue (used by the
     * feedback path). Cleared once a session attempt resolves either way.
     */
    fun requestSession() {
        wantSession = true
    }

    /** Forget the current session. The next action will open a fresh one if needed. */
    fun invalidateSession() {
        sessionToken = null
        sessionExpiresAtMs = 0L
    }

    // --- The driver loop ---------------------------------------------------

    /**
     * What to do next. Call in a loop: perform the returned request, feed the
     * response back through the matching onXxxResult method, then ask again.
     * [RavensightAction.Idle] and [RavensightAction.WaitUntil] end the loop.
     */
    fun nextAction(): RavensightAction {
        if (!enabledLocally) return RavensightAction.Idle
        if (!settingsChecked) return RavensightAction.CheckSettings
        if (!trackingEnabled) return RavensightAction.Idle

        val now = nowMs()
        if (now < nextAttemptAtMs) return RavensightAction.WaitUntil(nextAttemptAtMs)

        if (!isSessionValid()) {
            return if (queue.isNotEmpty() || wantSession) {
                RavensightAction.OpenSession
            } else {
                RavensightAction.Idle
            }
        }

        if (queue.isEmpty()) return RavensightAction.Idle

        val size = minOf(batchLimit, queue.size)
        inFlightBatchSize = size
        return RavensightAction.SendBatch(queue.take(size))
    }

    // --- Response handlers -------------------------------------------------

    /**
     * Feed back the GET /settings response. [status] is the HTTP status, or 0
     * for a network failure. An unreachable or malformed settings endpoint
     * assumes tracking is on, matching the Godot SDK, so a settings outage
     * never silently loses a session. Returns the resulting kill switch state.
     */
    fun onSettingsResult(status: Int, trackingEnabledValue: Boolean?): Boolean {
        settingsChecked = true
        trackingEnabled = if (status == 200 && trackingEnabledValue != null) {
            trackingEnabledValue
        } else {
            true
        }
        if (!trackingEnabled) clearQueue()
        return trackingEnabled
    }

    /**
     * Feed back the POST /session response. [status] is the HTTP status, or 0
     * for a network failure. [expiresAtSec] and [expiresInSec] come from the
     * response body when present; absent both, expiry defaults to 24 hours.
     * [retryAfterSec] is the parsed Retry-After header, if any.
     */
    fun onSessionResult(
        status: Int,
        token: String?,
        expiresAtSec: Long? = null,
        expiresInSec: Long? = null,
        retryAfterSec: Long? = null,
        errorCode: String? = null,
    ): SessionOutcome {
        wantSession = false

        if (status == 201 && !token.isNullOrEmpty()) {
            sessionToken = token
            sessionExpiresAtMs = when {
                expiresAtSec != null && expiresAtSec > 0 -> expiresAtSec * 1000L
                else -> nowMs() + (expiresInSec ?: 86_400L) * 1000L
            }
            sessions++
            resetBackoff()
            return SessionOutcome.Ready
        }

        if (status == 429) {
            val reason = errorCode ?: "rate_limited"
            scheduleRetry(retryAfterSec?.let { it * 1000L })
            return SessionOutcome.RateLimited(reason)
        }

        if (status == 201) {
            // 201 without a token: a malformed response we cannot use.
            scheduleRetry(null)
            return SessionOutcome.Failed(errorCode ?: "malformed_response")
        }

        scheduleRetry(null)
        return SessionOutcome.Failed(errorCode ?: httpReason(status))
    }

    /**
     * Feed back the POST /track/batch response for the batch handed out by the
     * last [nextAction]. [status] is the HTTP status, or 0 for a network
     * failure. Events leave the queue only on 202.
     */
    fun onBatchResult(
        status: Int,
        retryAfterSec: Long? = null,
        errorCode: String? = null,
    ): BatchOutcome {
        val batchSize = inFlightBatchSize
        inFlightBatchSize = 0

        when (status) {
            202 -> {
                val removed = minOf(batchSize, queue.size)
                repeat(removed) { queue.removeFirst() }
                sent += removed
                flushes++
                batchLimit = config.maxBatchSize
                consecutiveAuthFailures = 0
                resetBackoff()
                return BatchOutcome.Flushed(removed)
            }

            401 -> {
                // Session expired or revoked. The batch stays queued and is
                // re-sent as soon as a fresh session is issued. Repeated 401s
                // on freshly issued tokens fall back to the backoff schedule
                // instead of looping hot.
                invalidateSession()
                consecutiveAuthFailures++
                if (consecutiveAuthFailures > 2) scheduleRetry(null)
                return BatchOutcome.SessionExpired
            }

            429 -> {
                scheduleRetry(retryAfterSec?.let { it * 1000L })
                return BatchOutcome.RateLimited(errorCode ?: "rate_limited")
            }

            400 -> {
                if (batchSize > 1) {
                    batchLimit = (batchSize / 2).coerceAtLeast(1)
                    return BatchOutcome.BatchSplit(batchLimit)
                }
                // A single event the server will never take. Drop it so the
                // rest of the queue is not blocked behind it forever.
                if (queue.isNotEmpty()) {
                    queue.removeFirst()
                    dropped++
                }
                batchLimit = config.maxBatchSize
                return BatchOutcome.EventRejected
            }

            else -> {
                scheduleRetry(null)
                return BatchOutcome.Failed(errorCode ?: httpReason(status))
            }
        }
    }

    // --- Backoff -----------------------------------------------------------

    private fun resetBackoff() {
        backoffMs = config.initialRetryMs
        nextAttemptAtMs = 0L
    }

    /**
     * Blocks network attempts for [explicitWaitMs] when the server said how
     * long (Retry-After), otherwise for the current backoff delay. Either way
     * the stored backoff doubles up to the ceiling, matching the Godot SDK.
     */
    private fun scheduleRetry(explicitWaitMs: Long?) {
        val wait = if (explicitWaitMs != null && explicitWaitMs > 0) explicitWaitMs else backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(config.maxBackoffMs)
        nextAttemptAtMs = nowMs() + wait.coerceAtLeast(500L)
    }

    private fun clearQueue() {
        dropped += queue.size
        queue.clear()
    }

    private fun httpReason(status: Int): String =
        if (status <= 0) "network_error" else "http_$status"
}
