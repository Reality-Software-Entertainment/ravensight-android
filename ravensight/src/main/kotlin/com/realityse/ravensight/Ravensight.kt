package com.realityse.ravensight

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.realityse.ravensight.core.BatchOutcome
import com.realityse.ravensight.core.RavensightAction
import com.realityse.ravensight.core.RavensightCore
import com.realityse.ravensight.core.RavensightCoreConfig
import com.realityse.ravensight.core.RavensightEvent
import com.realityse.ravensight.core.SessionOutcome
import java.util.UUID
import org.json.JSONObject

/**
 * Ravensight analytics for Android. One call to [initialize] from your
 * Application (or first Activity) reads the server kill switch, opens a
 * session and starts flushing queued events every few seconds and whenever
 * the app leaves the foreground.
 *
 * Every public method is non blocking and safe to call from any thread. All
 * queue and network work happens on a single background HandlerThread. The
 * SDK has no third party dependencies.
 */
object Ravensight {

    private const val TAG = "Ravensight"
    private const val PREFS = "ravensight"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var controller: Controller? = null

    /** True once [initialize] has been called. */
    val isInitialized: Boolean
        get() = controller != null

    /** True once a session token has been issued. */
    val isReady: Boolean
        get() = controller?.ready ?: false

    /** True unless disabled locally or by the server kill switch. */
    val isEnabled: Boolean
        get() = controller?.active ?: false

    /** The anonymous device id reported to the server, or null before [initialize]. */
    val deviceId: String?
        get() = controller?.deviceId

    /**
     * Starts the SDK. Call once, as early as you like; events tracked before
     * the session is ready are queued and sent once it is. Extra calls are
     * ignored.
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(context: Context, config: RavensightConfig, listener: RavensightListener? = null) {
        synchronized(this) {
            if (controller != null) {
                Log.w(TAG, "initialize called twice, ignoring")
                return
            }
            require(config.ingestKey.isNotBlank()) { "Ravensight: ingestKey is required (your gt_live_... key)" }
            controller = Controller(context.applicationContext, config, listener).also { it.start() }
        }
    }

    /** Convenience overload: everything at its default except the key. */
    @JvmStatic
    fun initialize(context: Context, ingestKey: String) {
        initialize(context, RavensightConfig(ingestKey = ingestKey))
    }

    /**
     * Queues an event for delivery in the next batch. [data] values may be
     * strings, numbers, booleans, nulls, nested maps or lists. Returns false
     * when tracking is off or the SDK is not initialized.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, data: Map<String, Any?> = emptyMap()): Boolean {
        val c = controller ?: run {
            Log.w(TAG, "track called before initialize, event dropped: $eventName")
            return false
        }
        return c.track(eventName, data)
    }

    /** Asks for an immediate flush attempt of anything queued. */
    @JvmStatic
    fun flush() {
        controller?.requestFlush()
    }

    /**
     * Submits free form player feedback. [category] and [rating] are
     * optional; pass a rating in 1..5 if you have one, or 0 to omit it.
     * The outcome arrives via [RavensightListener.onFeedbackSubmitted] or
     * [RavensightListener.onFeedbackFailed].
     */
    @JvmStatic
    @JvmOverloads
    fun submitFeedback(message: String, category: String? = null, rating: Int = 0) {
        val c = controller ?: run {
            Log.w(TAG, "submitFeedback called before initialize")
            return
        }
        c.submitFeedback(message, category, rating)
    }

    /**
     * EXPERIMENTAL: fetches AI generated design suggestions for your game.
     * Empty until the game has accumulated enough data for weekly digests,
     * and the entry shape may change; do not build critical game logic on it.
     * The result arrives via [RavensightListener.onSuggestionsReceived].
     */
    @JvmStatic
    fun fetchSuggestions() {
        controller?.fetchSuggestions()
    }

    /**
     * Local privacy toggle for an opt in or opt out setting in your game.
     * Disabling stops all sending and discards anything still queued.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        controller?.setEnabled(enabled)
    }

    /** Replaces the listener passed to [initialize]. Null clears it. */
    @JvmStatic
    fun setListener(listener: RavensightListener?) {
        controller?.listener = listener
    }

    // -----------------------------------------------------------------------

    private class Controller(
        private val app: Context,
        private val config: RavensightConfig,
        @Volatile var listener: RavensightListener?,
    ) : Application.ActivityLifecycleCallbacks {

        private val apiUrl = RavensightConfig.normalizeApiUrl(config.apiUrl)
        private val http = RavensightHttp(config.requestTimeoutMs)
        private val thread = HandlerThread("Ravensight").apply { start() }
        private val handler = Handler(thread.looper)
        private val main = Handler(Looper.getMainLooper())
        private val gameVersion = config.gameVersion.ifEmpty { appVersionName() }

        /** Owned by the worker thread; never touched from any other thread. */
        private val core = RavensightCore(
            RavensightCoreConfig(maxQueueSize = config.maxQueueSize),
        ) { System.currentTimeMillis() }

        val deviceId: String = loadOrCreateDeviceId()

        @Volatile var ready = false
        @Volatile var active = config.enabled
        @Volatile private var killSwitchAnnounced = false

        private var startedActivities = 0
        private var everForegrounded = false
        private val pumpRunnable = Runnable { pump() }
        private val tickRunnable = object : Runnable {
            override fun run() {
                pump()
                if (config.flushIntervalMs > 0) handler.postDelayed(this, config.flushIntervalMs)
            }
        }

        fun start() {
            (app as? Application)?.registerActivityLifecycleCallbacks(this)
            handler.post {
                core.setEnabled(config.enabled)
                if (config.trackLifecycleEvents) core.track("game_started")
                pump()
            }
            if (config.flushIntervalMs > 0) handler.postDelayed(tickRunnable, config.flushIntervalMs)
        }

        fun track(eventName: String, data: Map<String, Any?>): Boolean {
            if (!active) return false
            // Copy so later mutation of the caller's map cannot race the worker.
            val snapshot = if (data.isEmpty()) emptyMap() else HashMap(data)
            handler.post { core.track(eventName, snapshot) }
            return true
        }

        fun requestFlush() {
            handler.post { pump() }
        }

        fun setEnabled(enabled: Boolean) {
            handler.post {
                core.setEnabled(enabled)
                active = core.isActive
                if (enabled) pump()
            }
        }

        // --- The driver loop, worker thread only ---------------------------

        private fun pump() {
            handler.removeCallbacks(pumpRunnable)
            while (true) {
                when (val action = core.nextAction()) {
                    is RavensightAction.Idle -> return

                    is RavensightAction.WaitUntil -> {
                        val delay = (action.atMs - System.currentTimeMillis()).coerceAtLeast(1L)
                        handler.postDelayed(pumpRunnable, delay)
                        return
                    }

                    is RavensightAction.CheckSettings -> checkSettings()
                    is RavensightAction.OpenSession -> openSession()
                    is RavensightAction.SendBatch -> sendBatch(action.events)
                }
            }
        }

        private fun checkSettings() {
            val result = http.request(
                "GET", "$apiUrl/settings",
                mapOf("X-API-Key" to config.ingestKey), null,
            )
            val value = result.json?.takeIf { it.has("trackingEnabled") }?.optBoolean("trackingEnabled")
            val enabled = core.onSettingsResult(result.status, value)
            active = core.isActive
            if (!enabled && !killSwitchAnnounced) {
                killSwitchAnnounced = true
                Log.i(TAG, "tracking disabled by server kill switch")
                emit { it.onTrackingDisabled("server") }
            } else if (result.status != 200) {
                Log.w(TAG, "settings check failed (HTTP ${result.status}), assuming tracking enabled")
            }
        }

        private fun openSession() {
            val body = JSONObject()
                .put("deviceId", deviceId)
                .put("gameVersion", gameVersion)
                .put("platform", "android")
            val result = http.request(
                "POST", "$apiUrl/session",
                mapOf("X-API-Key" to config.ingestKey), body.toString(),
            )
            val json = result.json
            val outcome = core.onSessionResult(
                status = result.status,
                token = json?.optString("token")?.takeIf { it.isNotEmpty() },
                expiresAtSec = json?.optLong("expiresAt")?.takeIf { it > 0 },
                expiresInSec = json?.optLong("expiresIn")?.takeIf { it > 0 },
                retryAfterSec = result.retryAfterSec,
                errorCode = json?.optString("error")?.takeIf { it.isNotEmpty() },
            )
            when (outcome) {
                is SessionOutcome.Ready -> {
                    ready = true
                    verbose("session ready")
                    emit { it.onSessionReady() }
                }
                is SessionOutcome.RateLimited -> {
                    Log.w(TAG, "session creation rate limited (${outcome.reason})")
                    emit { it.onSessionFailed(outcome.reason) }
                }
                is SessionOutcome.Failed -> {
                    Log.w(TAG, "session creation failed (${outcome.reason}), will retry")
                    emit { it.onSessionFailed(outcome.reason) }
                }
            }
        }

        private fun sendBatch(events: List<RavensightEvent>) {
            val token = core.sessionToken ?: return
            val payload = JSONObject().put(
                "events",
                RavensightJson.toJsonValue(
                    events.map {
                        mapOf("event" to it.name, "data" to it.data, "timestamp" to it.timestampSec)
                    },
                ),
            )
            val result = http.request(
                "POST", "$apiUrl/track/batch",
                mapOf("X-Session-Token" to token), payload.toString(),
            )
            val errorCode = result.json?.optString("error")?.takeIf { it.isNotEmpty() }
            when (val outcome = core.onBatchResult(result.status, result.retryAfterSec, errorCode)) {
                is BatchOutcome.Flushed -> {
                    verbose("flushed ${outcome.count} events")
                    emit { it.onEventsFlushed(outcome.count) }
                }
                is BatchOutcome.SessionExpired -> {
                    ready = false
                    Log.w(TAG, "session invalid or expired, refreshing")
                }
                is BatchOutcome.RateLimited -> {
                    Log.w(TAG, "track/batch rate limited (${outcome.reason})")
                    emit { it.onFlushFailed(outcome.reason) }
                }
                is BatchOutcome.BatchSplit ->
                    Log.w(TAG, "batch rejected as oversize, splitting to ${outcome.newLimit}")
                is BatchOutcome.EventRejected ->
                    Log.w(TAG, "dropped one event rejected with HTTP 400")
                is BatchOutcome.Failed -> {
                    Log.w(TAG, "batch flush failed (${outcome.reason}), will retry")
                    emit { it.onFlushFailed(outcome.reason) }
                }
            }
        }

        // --- Feedback and suggestions, worker thread only -------------------

        fun submitFeedback(message: String, category: String?, rating: Int) {
            handler.post {
                if (!core.isActive) {
                    Log.w(TAG, "tracking disabled, feedback not sent")
                    emit { it.onFeedbackFailed("tracking_disabled") }
                    return@post
                }
                if (message.isBlank()) {
                    Log.w(TAG, "feedback message is empty")
                    emit { it.onFeedbackFailed("empty_message") }
                    return@post
                }
                val token = core.sessionToken?.takeIf { core.isSessionValid() }
                if (token == null) {
                    Log.w(TAG, "no valid session yet, cannot submit feedback")
                    emit { it.onFeedbackFailed("no_session") }
                    core.requestSession()
                    pump()
                    return@post
                }

                val payload = JSONObject().put("message", message)
                if (!category.isNullOrEmpty()) payload.put("category", category)
                if (rating > 0) payload.put("rating", rating)

                val result = http.request(
                    "POST", "$apiUrl/feedback",
                    mapOf("X-Session-Token" to token), payload.toString(),
                )
                if (result.status == 201) {
                    emit { it.onFeedbackSubmitted() }
                } else {
                    Log.w(TAG, "feedback submission failed (HTTP ${result.status})")
                    emit { it.onFeedbackFailed(result.errorCode("http_${result.status}")) }
                }
            }
        }

        fun fetchSuggestions() {
            handler.post {
                val result = http.request(
                    "GET", "$apiUrl/agent/suggestions",
                    mapOf("X-API-Key" to config.ingestKey), null,
                )
                val suggestions: List<Any?> = if (result.status == 200) {
                    val array = result.json?.optJSONArray("suggestions")
                    if (array != null) RavensightJson.toPlain(array) as List<Any?> else emptyList()
                } else {
                    Log.w(TAG, "fetchSuggestions failed (HTTP ${result.status})")
                    emptyList()
                }
                emit { it.onSuggestionsReceived(suggestions) }
            }
        }

        // --- Activity lifecycle: pause, resume and background flushes -------

        override fun onActivityStarted(activity: Activity) {
            val cameToForeground = startedActivities == 0
            startedActivities++
            if (cameToForeground && everForegrounded && config.trackLifecycleEvents) {
                handler.post {
                    core.track("game_resumed")
                    pump()
                }
            }
            everForegrounded = true
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                handler.post {
                    if (config.trackLifecycleEvents) core.track("game_paused")
                    if (config.flushOnBackground) pump()
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}

        // --- Helpers ---------------------------------------------------------

        private fun emit(call: (RavensightListener) -> Unit) {
            val target = listener ?: return
            main.post { call(target) }
        }

        private fun verbose(message: String) {
            if (config.verboseLogging) Log.i(TAG, message)
        }

        private fun appVersionName(): String = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        private fun loadOrCreateDeviceId(): String {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotEmpty() }?.let { return it }
            val id = "dev_" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }
    }
}
