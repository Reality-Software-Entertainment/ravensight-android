package com.realityse.ravensight.core

import kotlin.random.Random

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

/**
 * The reason vocabulary shared by every Ravensight SDK. [RavensightCore.reason]
 * is one of these, or null when nothing is wrong.
 */
object RavensightReason {
    /** No ingest key: the SDK is inert for the run. */
    const val NO_KEY: String = "no_key"

    /** Turned off by the game (`enabled = false`) or by the player's saved opt-out. */
    const val DISABLED: String = "disabled"

    /**
     * The ingest key was refused (401, or a 403 that is not one of the
     * others). On the settings check or a session open it ends the run.
     */
    const val INVALID_KEY: String = "invalid_key"

    /** The server switched tracking off for this game. Permanent for the run. */
    const val TRACKING_DISABLED: String = "tracking_disabled"

    /** The network was unreachable or timed out. Retried with backoff. */
    const val OFFLINE: String = "offline"

    /** The server asked the SDK to slow down (429, or 503 with Retry-After). */
    const val RATE_LIMITED: String = "rate_limited"

    /** Opening a session failed for another reason, or a playtest token was refused. */
    const val SESSION_FAILED: String = "session_failed"

    /** The playtest job this run belongs to has closed. Permanent for the run. */
    const val PLAYTEST_JOB_CLOSED: String = "playtest_job_closed"

    /**
     * What a 401 or 403 answer means, from any endpoint: [INVALID_KEY],
     * [TRACKING_DISABLED], [PLAYTEST_JOB_CLOSED], or [SESSION_FAILED] for an
     * invalid playtest token. Null for any other status. A batch 401 is a
     * session expiry instead and is not passed through here.
     */
    fun forRefusal(status: Int, errorCode: String?): String? = when (status) {
        401 -> INVALID_KEY
        403 -> when (errorCode) {
            "tracking_disabled" -> TRACKING_DISABLED
            "playtest_job_closed" -> PLAYTEST_JOB_CLOSED
            "invalid_playtest_token" -> SESSION_FAILED
            else -> INVALID_KEY
        }
        else -> null
    }
}

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
    /** 0 <= x < 1, for the backoff jitter. Tests fix it; 0.5 is no jitter. */
    val random: () -> Double = { Random.nextDouble() },
) {
    companion object {
        const val MAX_BATCH_SIZE: Int = 50
        const val DEFAULT_QUEUE_CAP: Int = 500
        const val DEFAULT_RETRY_MS: Long = 10_000L
        const val MAX_BACKOFF_MS: Long = 300_000L

        /** Retry waits without a Retry-After vary by this fraction either way. */
        const val BACKOFF_JITTER: Double = 0.2
        const val MIN_RETRY_MS: Long = 500L
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

    /** 401 or 403: [reason] is a [RavensightReason] value and automatic retries stop. */
    class Refused(val reason: String) : SessionOutcome()
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

    /** 403: [reason] is a [RavensightReason] value and automatic retries stop. */
    class Refused(val reason: String) : BatchOutcome()
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
 * re-authentication on a batch 401, a refused key that stops automatic
 * retries, Retry-After on 429 and 503, exponential backoff from 10s to a 5
 * minute ceiling, and oversize splitting on 400.
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
    private val queueCap = config.maxQueueSize.coerceAtLeast(1)
    private val maxBatch = config.maxBatchSize.coerceAtLeast(1)

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
    private var batchLimit = maxBatch
    private var consecutiveAuthFailures = 0
    private var inFlightBatchSize = 0

    /** Set by a refused ingest key. Automatic attempts stop; [retryOnce] allows one more. */
    var keyRefused: Boolean = false
        private set

    /** A permanent stop for this run ([RavensightReason] value), or null. */
    var stopReason: String? = null
        private set

    private var lastFailure: String? = null

    private var sent = 0L
    private var dropped = 0L
    private var flushes = 0L
    private var sessions = 0L

    // --- State queries -----------------------------------------------------

    /**
     * True unless disabled locally, by the server kill switch or by a
     * permanent stop. While the key is refused this stays true so events keep
     * queueing (the cap applies), but nothing is sent automatically.
     */
    val isActive: Boolean
        get() = enabledLocally && trackingEnabled && stopReason == null

    /**
     * Why the SDK is not delivering, as a [RavensightReason] value, or null
     * when nothing is known to be wrong.
     */
    val reason: String?
        get() = when {
            !enabledLocally -> RavensightReason.DISABLED
            stopReason != null -> stopReason
            !trackingEnabled -> RavensightReason.TRACKING_DISABLED
            keyRefused -> RavensightReason.INVALID_KEY
            else -> lastFailure
        }

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
     * settings check has answered. [timestampSec] is when the event
     * happened, in Unix seconds; null means now. [atHead] puts the event
     * first in the queue (used for game_started, which opens a session).
     * Returns false when tracking is off, and never throws: a blank name
     * also returns false.
     */
    fun track(
        name: String,
        data: Map<String, Any?> = emptyMap(),
        timestampSec: Long? = null,
        atHead: Boolean = false,
    ): Boolean {
        if (name.isBlank()) return false
        if (!isActive) return false

        while (queue.size >= queueCap) {
            queue.removeFirst()
            dropped++
        }
        val event = RavensightEvent(name, data, timestampSec ?: (nowMs() / 1000L))
        if (atHead) queue.addFirst(event) else queue.addLast(event)
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

    /**
     * An explicit flush after a refused key: allow exactly one more attempt.
     * Another refusal sets [keyRefused] again.
     */
    fun retryOnce() {
        if (!keyRefused) return
        keyRefused = false
        nextAttemptAtMs = 0L
    }

    /**
     * Stops for the rest of the run with [reason]: the queue is cleared, the
     * session is forgotten and [nextAction] answers Idle from now on.
     */
    fun stopForRun(reason: String) {
        if (stopReason != null) return
        stopReason = reason
        clearQueue()
        invalidateSession()
    }

    /**
     * Applies a 401 or 403 answer from any endpoint ([RavensightReason.forRefusal]).
     * Returns the reason so the caller can report it.
     */
    fun onRefused(reason: String): String {
        when (reason) {
            RavensightReason.INVALID_KEY -> keyRefused = true
            RavensightReason.TRACKING_DISABLED -> {
                trackingEnabled = false
                clearQueue()
                invalidateSession()
            }
            else -> stopForRun(reason)
        }
        return reason
    }

    /**
     * The key was refused by the settings check or a session open (401, or a
     * 403 that is not the kill switch or a playtest refusal): retrying cannot
     * help, so the run ends. The queue is cleared and [nextAction] answers
     * Idle from now on, an explicit flush ([retryOnce]) included.
     */
    private fun refuseKeyForRun() {
        keyRefused = true
        stopForRun(RavensightReason.INVALID_KEY)
    }

    /** Counts events dropped before they reached the queue (the 8 KB cap). */
    fun countDropped(count: Int) {
        dropped += count.coerceAtLeast(0)
    }

    /** Discards everything queued, counting it as dropped. */
    fun clearQueue() {
        dropped += queue.size
        queue.clear()
    }

    // --- The driver loop ---------------------------------------------------

    /**
     * What to do next. Call in a loop: perform the returned request, feed the
     * response back through the matching onXxxResult method, then ask again.
     * [RavensightAction.Idle] and [RavensightAction.WaitUntil] end the loop.
     */
    fun nextAction(): RavensightAction {
        if (!enabledLocally || stopReason != null || keyRefused) return RavensightAction.Idle
        if (!trackingEnabled) return RavensightAction.Idle

        val now = nowMs()
        if (now < nextAttemptAtMs) return RavensightAction.WaitUntil(nextAttemptAtMs)
        if (!settingsChecked) return RavensightAction.CheckSettings

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
     * for a network failure. A 401 or 403 is a refusal ([onRefused]); the
     * settings check is then repeated on the next allowed attempt. A 429, or
     * a 503 with [retryAfterSec], waits that long and checks again. Any other
     * unreachable or malformed answer assumes tracking is on, so a settings
     * outage never silently loses a session. Returns the resulting kill
     * switch state.
     */
    fun onSettingsResult(
        status: Int,
        trackingEnabledValue: Boolean?,
        errorCode: String? = null,
        retryAfterSec: Long? = null,
    ): Boolean {
        val refusal = RavensightReason.forRefusal(status, errorCode)
        if (refusal != null) {
            if (refusal == RavensightReason.INVALID_KEY) refuseKeyForRun() else onRefused(refusal)
            return trackingEnabled
        }
        if (status == 429 || (status == 503 && retryAfterSec != null)) {
            // Asked to slow down: check the settings again once the wait is over.
            lastFailure = RavensightReason.RATE_LIMITED
            scheduleRetry(retryAfterSec?.let { it * 1000L })
            return trackingEnabled
        }
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
            lastFailure = null
            resetBackoff()
            return SessionOutcome.Ready
        }

        RavensightReason.forRefusal(status, errorCode)?.let {
            if (it == RavensightReason.INVALID_KEY) {
                refuseKeyForRun()
                return SessionOutcome.Refused(it)
            }
            return SessionOutcome.Refused(onRefused(it))
        }

        if (status == 429 || (status == 503 && retryAfterSec != null)) {
            val reason = errorCode ?: "rate_limited"
            lastFailure = RavensightReason.RATE_LIMITED
            scheduleRetry(retryAfterSec?.let { it * 1000L })
            return SessionOutcome.RateLimited(reason)
        }

        lastFailure = if (status <= 0) RavensightReason.OFFLINE else RavensightReason.SESSION_FAILED
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
                batchLimit = maxBatch
                consecutiveAuthFailures = 0
                lastFailure = null
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

            403 -> {
                val reason = RavensightReason.forRefusal(status, errorCode) ?: RavensightReason.INVALID_KEY
                return BatchOutcome.Refused(onRefused(reason))
            }

            429 -> {
                lastFailure = RavensightReason.RATE_LIMITED
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
                batchLimit = maxBatch
                return BatchOutcome.EventRejected
            }

            else -> {
                if (status == 503 && retryAfterSec != null) {
                    lastFailure = RavensightReason.RATE_LIMITED
                    scheduleRetry(retryAfterSec * 1000L)
                    return BatchOutcome.RateLimited(errorCode ?: "storage_unavailable")
                }
                lastFailure = RavensightReason.OFFLINE
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
     * long (Retry-After, obeyed exactly), otherwise for the current backoff
     * delay with plus or minus 20 percent jitter, never past the ceiling.
     * Either way the stored backoff doubles up to the ceiling, matching the
     * Godot SDK, and no wait is shorter than half a second.
     */
    private fun scheduleRetry(explicitWaitMs: Long?) {
        val wait = if (explicitWaitMs != null && explicitWaitMs > 0) {
            explicitWaitMs
        } else {
            val roll = config.random().coerceIn(0.0, 1.0)
            val factor = 1 - RavensightCoreConfig.BACKOFF_JITTER + 2 * RavensightCoreConfig.BACKOFF_JITTER * roll
            Math.round(backoffMs * factor).coerceAtMost(config.maxBackoffMs)
        }
        backoffMs = (backoffMs * 2).coerceAtMost(config.maxBackoffMs)
        nextAttemptAtMs = nowMs() + wait.coerceAtLeast(RavensightCoreConfig.MIN_RETRY_MS)
    }

    private fun httpReason(status: Int): String =
        if (status <= 0) "network_error" else "http_$status"
}
