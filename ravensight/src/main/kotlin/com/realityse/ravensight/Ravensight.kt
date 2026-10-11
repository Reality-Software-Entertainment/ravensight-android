package com.realityse.ravensight

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.realityse.ravensight.core.BatchOutcome
import com.realityse.ravensight.core.RavensightAction
import com.realityse.ravensight.core.RavensightCore
import com.realityse.ravensight.core.RavensightCoreConfig
import com.realityse.ravensight.core.RavensightEvent
import com.realityse.ravensight.core.RavensightReason
import com.realityse.ravensight.core.SessionOutcome
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ravensight analytics for Android. Call [initialize] once from your
 * Application's onCreate. Nothing touches the network until the app first
 * comes to the foreground: then the SDK queues game_started, reads the
 * server kill switch, opens a session and flushes queued events every few
 * seconds, at 50 queued, on [flush] and whenever the app leaves the
 * foreground.
 *
 * Every public method is non blocking, safe to call from any thread, and
 * never throws. All queue and network work happens on a single background
 * HandlerThread.
 */
object Ravensight {

    /** This SDK's version. */
    const val VERSION: String = "0.2.0"

    internal const val TAG = "Ravensight"
    internal const val PREFS = "ravensight"
    internal const val KEY_DEVICE_ID = "device_id"
    internal const val KEY_OPT_OUT = "tracking_opt_out"
    internal const val KEY_MARKER_TOKEN = "exit_marker_token"
    internal const val KEY_MARKER_EXPIRES_AT = "exit_marker_expires_at_ms"
    internal const val KEY_MARKER_BACKGROUNDED_AT = "exit_marker_backgrounded_at_ms"

    internal const val EXTRA_PLAYTEST_TOKEN = "ravensight_playtest_token"
    internal const val EXTRA_PLAYTEST_RUN_ID = "ravensight_playtest_run_id"
    internal const val ENV_PLAYTEST_TOKEN = "RAVENSIGHT_PLAYTEST_TOKEN"
    internal const val ENV_PLAYTEST_RUN_ID = "RAVENSIGHT_PLAYTEST_RUN_ID"

    /** Manifest meta-data that lets a release build honour the playtest intent extras. */
    const val META_ALLOW_PLAYTEST_INTENT: String = "com.realityse.ravensight.ALLOW_PLAYTEST_INTENT"

    @Volatile
    private var controller: Controller? = null

    /** [setEnabled] called before [initialize]; applied by [initialize]. */
    @Volatile
    private var pendingEnabled: Boolean? = null

    /** [setPlaytestContext] called before [initialize]; applied by [initialize]. */
    @Volatile
    private var pendingPlaytest: Pair<String, String?>? = null

    /** True once [initialize] has been called. */
    @JvmStatic
    val isInitialized: Boolean
        get() = controller != null

    /** True while a session token is held and events are flowing. */
    @JvmStatic
    val isReady: Boolean
        get() = controller?.ready ?: false

    /**
     * True while events are accepted: not disabled by the game, not opted
     * out by the player, not switched off by the server and not stopped.
     */
    @JvmStatic
    val isEnabled: Boolean
        get() = controller?.active ?: false

    /**
     * The anonymous device id reported to the server. Null before
     * [initialize], and null until the first Activity is created (the SDK
     * first checks whether this is a playtest run, whose id is never saved).
     */
    @JvmStatic
    val deviceId: String?
        get() = controller?.deviceId

    /**
     * Why the SDK is not delivering, or null when it is healthy: "disabled",
     * "invalid_key", "tracking_disabled", "offline", "rate_limited",
     * "session_failed" or "playtest_job_closed". Changes are reported through
     * [RavensightListener.onStatusChanged].
     */
    @JvmStatic
    val statusReason: String?
        get() = controller?.statusReason

    /**
     * The current session token, or null until a session opens. A game
     * server can pass it to Ravensight as `joinToken` to add server events
     * to this player's session.
     */
    @JvmStatic
    val sessionToken: String?
        get() = controller?.sessionToken

    /** True when this run is a Ravensight Playtest run (a token was supplied). */
    @JvmStatic
    val isPlaytest: Boolean
        get() = controller?.isPlaytest ?: false

    /**
     * Starts the SDK. Call once, from Application.onCreate. Events tracked
     * before the session is ready are queued. Extra calls are ignored. A
     * blank ingest key logs an error and leaves the SDK stopped with
     * [statusReason] "invalid_key"; it never throws.
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(context: Context, config: RavensightConfig, listener: RavensightListener? = null) {
        initialize(context, config, listener, RavensightDeps())
    }

    /** Convenience overload: everything at its default except the key. */
    @JvmStatic
    fun initialize(context: Context, ingestKey: String) {
        initialize(context, RavensightConfig(ingestKey = ingestKey))
    }

    internal fun initialize(
        context: Context,
        config: RavensightConfig,
        listener: RavensightListener?,
        deps: RavensightDeps,
    ) = safely("initialize") {
        synchronized(this) {
            if (controller != null) {
                Log.w(TAG, "initialize called twice, ignoring")
                return@synchronized
            }
            val c = Controller(
                context.applicationContext ?: context,
                config,
                listener,
                deps,
                pendingEnabled,
                pendingPlaytest,
            )
            controller = c
            c.start(context)
        }
    }

    /**
     * Queues an event for delivery in the next batch. [data] values may be
     * strings, numbers, booleans, nulls, nested maps or lists. Returns false,
     * and logs a warning, for a blank name; returns false when tracking is
     * off or the SDK is not initialized. Never throws.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, data: Map<String, Any?> = emptyMap()): Boolean =
        safely("track", false) {
            val c = controller
            if (c == null) {
                Log.w(TAG, "track called before initialize, event dropped: $eventName")
                false
            } else {
                c.track(eventName, data)
            }
        }

    /**
     * Asks for an immediate flush of anything queued. After a refused key
     * this makes exactly one more attempt.
     */
    @JvmStatic
    fun flush() = safely("flush") { controller?.requestFlush() }

    /**
     * Submits player feedback. [category] is optional and must be one of
     * [RavensightConfig.FEEDBACK_CATEGORIES]; [rating] is 0 to omit it or an
     * integer from 1 to 5. Invalid values fail locally with no request. The
     * outcome arrives via [RavensightListener.onFeedbackSubmitted] or
     * [RavensightListener.onFeedbackFailed].
     */
    @JvmStatic
    @JvmOverloads
    fun submitFeedback(message: String, category: String? = null, rating: Int = 0) =
        safely("submitFeedback") {
            val c = controller
            if (c == null) {
                Log.w(TAG, "submitFeedback called before initialize")
            } else {
                c.submitFeedback(message, category, rating)
            }
        }

    /**
     * EXPERIMENTAL: fetches AI generated design suggestions for your game.
     * Empty until the game has accumulated enough data for weekly digests,
     * and the entry shape may change; do not build critical game logic on it.
     * The result arrives via [RavensightListener.onSuggestionsReceived], as
     * an empty list (with no request) while tracking is off.
     */
    @JvmStatic
    fun fetchSuggestions() = safely("fetchSuggestions") { controller?.fetchSuggestions() }

    /**
     * The game's own switch, matching [RavensightConfig.enabled]. Not saved.
     * False stops all sending and discards anything still queued. Callable
     * before [initialize], which then starts in that state. For the player's
     * own choice use [setTrackingEnabled], which is saved.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) = safely("setEnabled") {
        synchronized(this) {
            val c = controller
            if (c == null) pendingEnabled = enabled else c.setEnabledByGame(enabled)
        }
    }

    /**
     * The player's analytics choice, saved across launches. Writes the
     * choice synchronously, so it is safe to call before [initialize] (the
     * SDK reads it at start). Turning it off clears anything queued and stops
     * sending. A saved opt-out wins over `enabled = true`. Ignored, with one
     * log line, during a playtest run.
     */
    @JvmStatic
    fun setTrackingEnabled(context: Context, enabled: Boolean) = safely("setTrackingEnabled") {
        synchronized(this) {
            val c = controller
            if (c != null && c.isPlaytest) {
                Log.i(TAG, "setTrackingEnabled ignored during a playtest run")
                return@synchronized
            }
            prefs(context).edit().putBoolean(KEY_OPT_OUT, !enabled).commit()
            c?.setOptedOut(!enabled)
        }
    }

    /** The player's saved analytics choice: false after `setTrackingEnabled(context, false)`. */
    @JvmStatic
    fun isTrackingEnabled(context: Context): Boolean =
        safely("isTrackingEnabled", true) { !prefs(context).getBoolean(KEY_OPT_OUT, false) }

    /**
     * Forgets this install's analytics identity: mints and saves a new random
     * id, clears the queue, ends the session and returns the new id. Returns
     * "" before [initialize]; use the overload with a Context there. Ignored,
     * with one log line, during a playtest run.
     */
    @JvmStatic
    fun resetDeviceId(): String = safely("resetDeviceId", "") {
        val c = controller
        if (c == null) {
            Log.w(TAG, "resetDeviceId called before initialize; pass a Context to reset it now")
            ""
        } else {
            c.resetDeviceId()
        }
    }

    /** As [resetDeviceId], and also works before [initialize]. */
    @JvmStatic
    fun resetDeviceId(context: Context): String = safely("resetDeviceId", "") {
        synchronized(this) {
            val c = controller
            if (c != null) {
                c.resetDeviceId()
            } else {
                val id = newDeviceId()
                prefs(context).edit().putString(KEY_DEVICE_ID, id).commit()
                id
            }
        }
    }

    /**
     * Marks this run as a Ravensight Playtest run. Normally not needed: the
     * SDK reads the token and run id from [RavensightConfig], the launch
     * intent extras "ravensight_playtest_token" and
     * "ravensight_playtest_run_id", or the environment. Call before the
     * first Activity starts. The first source found wins.
     */
    @JvmStatic
    fun setPlaytestContext(token: String, runId: String?) = safely("setPlaytestContext") {
        synchronized(this) {
            val c = controller
            if (c == null) pendingPlaytest = token to runId else c.setPlaytestContext(token, runId, "setPlaytestContext")
        }
    }

    /** Replaces the listener passed to [initialize]. Null clears it. */
    @JvmStatic
    fun setListener(listener: RavensightListener?) = safely("setListener") {
        controller?.listener = listener
    }

    // -----------------------------------------------------------------------

    /** Test hook: drops the controller and anything pending, as if the process restarted. */
    internal fun resetForTest() {
        synchronized(this) {
            controller?.shutdownForTest()
            controller = null
            pendingEnabled = null
            pendingPlaytest = null
        }
    }

    internal val controllerForTest: Controller?
        get() = controller

    internal fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val random = SecureRandom()

    /** "dev_" plus 32 hex characters from 16 bytes of the platform CSPRNG. */
    internal fun newDeviceId(): String {
        val bytes = ByteArray(16)
        synchronized(random) { random.nextBytes(bytes) }
        return "dev_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private inline fun <T> safely(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        Log.e(TAG, "$what failed inside the SDK, ignored", t)
        fallback
    }

    private inline fun safely(what: String, block: () -> Unit) = safely(what, Unit, block)

    // -----------------------------------------------------------------------

    internal class Controller(
        private val app: Context,
        private val config: RavensightConfig,
        @Volatile var listener: RavensightListener?,
        private val deps: RavensightDeps,
        pendingEnabled: Boolean?,
        pendingPlaytest: Pair<String, String?>?,
    ) : Application.ActivityLifecycleCallbacks {

        private val apiUrl = RavensightConfig.normalizeApiUrl(config.apiUrl)
        private val transport: RavensightTransport = deps.transport ?: RavensightHttp(config.requestTimeoutMs)
        private val worker: RavensightWorker = deps.worker ?: HandlerWorker()
        private val mainPost: (Runnable) -> Unit = deps.mainPost ?: run {
            val main = Handler(Looper.getMainLooper())
            val post: (Runnable) -> Unit = { main.post(it) }
            post
        }
        private val clock = deps.clockMs
        private val prefs = prefs(app)
        private val gameVersion = config.gameVersion.ifEmpty { appVersionName() }

        /** Owned by the worker thread after construction; never touched from any other thread. */
        private val core = RavensightCore(
            RavensightCoreConfig(maxQueueSize = config.maxQueueSize),
        ) { clock() }

        // Mirrors read from any thread.
        @Volatile var deviceId: String? = null
        @Volatile var ready = false
        @Volatile var active = false
        @Volatile var statusReason: String? = null
        @Volatile var sessionToken: String? = null
        @Volatile var isPlaytest = false

        // Set from any thread, read on the worker.
        @Volatile private var enabledByGame: Boolean = pendingEnabled ?: config.enabled
        @Volatile private var optedOut: Boolean = prefs.getBoolean(KEY_OPT_OUT, false)

        /** Mirror: the server and the run still allow sending (no kill switch, no stop). */
        @Volatile private var serverAllows = true

        // Worker thread only.
        private var playtestChosen = false
        private var playtestToken: String? = null
        private var foregrounded = false
        private var inForeground = false
        private var backgroundedAtMs: Long? = null
        private var startedQueued = false
        private var exitedQueued = false
        private var tickScheduled = false
        private var lastPublishedReason: String? = null
        private val loggedOnce = HashSet<String>()

        // Main thread only.
        @Volatile private var firstActivitySeen = false
        private var startedActivities = 0
        private var processLifecycleObserved = false

        private val warnedReservedKeys = ConcurrentHashMap.newKeySet<String>()

        private val pumpRunnable = Runnable { runSafely("pump") { pump() } }
        private val tickRunnable = Runnable {
            runSafely("flush timer") {
                if (tickScheduled) {
                    worker.postDelayed(tickRunnableRef, config.flushIntervalMs)
                    pump()
                }
            }
        }
        private val tickRunnableRef: Runnable get() = tickRunnable

        init {
            if (config.ingestKey.isBlank()) {
                Log.e(TAG, "Ravensight: ingestKey is required (your gt_live_... key); sending stopped for this run")
                core.stopForRun(RavensightReason.INVALID_KEY)
            }
            val token = config.playtestToken?.takeIf { it.isNotBlank() }
            when {
                token != null -> choosePlaytest(token, config.playtestRunId, "config")
                pendingPlaytest != null -> choosePlaytest(pendingPlaytest.first, pendingPlaytest.second, "setPlaytestContext")
            }
            core.setEnabled(effectiveEnabled())
            publish()
        }

        fun start(context: Context) {
            if (deps.registerActivityCallbacks) (app as? Application)?.registerActivityLifecycleCallbacks(this)
            // initialize from an Activity: its intent may carry the playtest extras.
            (context as? Activity)?.let { readPlaytestExtras(it) }
            if (deps.observeProcessLifecycle) onMain { observeProcessLifecycle() }
        }

        private fun effectiveEnabled(): Boolean = enabledByGame && (isPlaytest || !optedOut)

        // --- Calls from game code, any thread -------------------------------

        fun track(eventName: String, data: Map<String, Any?>): Boolean {
            if (eventName.isBlank()) {
                Log.w(TAG, "track called with a blank event name, event dropped")
                return false
            }
            if (!active) return false
            val at = clock() / 1000L
            // Copy so later mutation of the caller's map cannot race the worker.
            val snapshot = if (data.isEmpty()) emptyMap() else HashMap(data)
            checkReservedKeys(eventName, snapshot)
            post("track") {
                core.track(eventName, snapshot, at)
                if (core.queueSize >= RavensightCoreConfig.MAX_BATCH_SIZE) pump()
            }
            return true
        }

        fun requestFlush() {
            post("flush") {
                core.retryOnce()
                pump()
            }
        }

        fun setEnabledByGame(enabled: Boolean) {
            enabledByGame = enabled
            active = serverAllows && effectiveEnabled()
            post("setEnabled") { applyEnabled() }
        }

        fun setOptedOut(value: Boolean) {
            optedOut = value
            active = serverAllows && effectiveEnabled()
            post("setTrackingEnabled") {
                applyEnabled()
                if (value) emit { it.onTrackingDisabled("player") }
            }
        }

        fun resetDeviceId(): String {
            if (isPlaytest) {
                Log.i(TAG, "resetDeviceId ignored during a playtest run")
                return deviceId ?: ""
            }
            val id = newDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, id).commit()
            deviceId = id
            post("resetDeviceId") {
                core.clearQueue()
                core.invalidateSession()
                deleteMarker()
            }
            return id
        }

        fun setPlaytestContext(token: String, runId: String?, source: String) {
            post("setPlaytestContext") { choosePlaytest(token, runId, source) }
        }

        fun submitFeedback(message: String, category: String?, rating: Int) {
            post("submitFeedback") { sendFeedback(message, category, rating) }
        }

        fun fetchSuggestions() {
            post("fetchSuggestions") { requestSuggestions() }
        }

        // --- Lifecycle signals, main thread -----------------------------------

        private fun observeProcessLifecycle() {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = onForegroundSignal()
                    override fun onStop(owner: LifecycleOwner) = onBackgroundSignal()
                })
                processLifecycleObserved = true
            } catch (t: Throwable) {
                Log.w(TAG, "process lifecycle unavailable, falling back to activity callbacks", t)
            }
        }

        /** True when the process lifecycle is driving foreground and background. */
        private fun processLifecycleWorks(): Boolean = processLifecycleObserved && try {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)
        } catch (t: Throwable) {
            false
        }

        internal fun onForegroundSignal() {
            val now = clock()
            post("foreground") { foreground(now) }
        }

        internal fun onBackgroundSignal() {
            val now = clock()
            post("background") { background(now) }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            readPlaytestExtras(activity)
        }

        override fun onActivityStarted(activity: Activity) {
            // Fallback only: the process lifecycle already debounces rotation.
            if (processLifecycleWorks()) return
            startedActivities++
            if (startedActivities == 1) onForegroundSignal()
        }

        override fun onActivityStopped(activity: Activity) {
            if (processLifecycleWorks()) return
            if (startedActivities == 0) return
            startedActivities--
            // A configuration change (rotation, dark mode, a resize) stops
            // the old Activity before the new one starts; that is not a
            // background. The new Activity's start then signals a foreground
            // that was never left, which changes nothing.
            if (startedActivities == 0 && !activity.isChangingConfigurations) onBackgroundSignal()
        }

        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}

        private fun readPlaytestExtras(activity: Activity) {
            if (firstActivitySeen) return
            firstActivitySeen = true
            var (token, runId) = try {
                val intent = activity.intent
                intent?.getStringExtra(EXTRA_PLAYTEST_TOKEN) to intent?.getStringExtra(EXTRA_PLAYTEST_RUN_ID)
            } catch (t: Throwable) {
                null to null
            }
            // Any app on the device can launch an exported Activity with
            // extras, and a playtest run ignores the player's opt-out. So the
            // extras count only in a debuggable build, or when the studio
            // opted in through the manifest meta-data.
            if (!token.isNullOrBlank() && !playtestIntentAllowed()) {
                Log.w(TAG, "playtest intent extras ignored: the app is not debuggable and the manifest has no $META_ALLOW_PLAYTEST_INTENT meta-data set to true")
                token = null
                runId = null
            }
            post("playtest extras") {
                if (!token.isNullOrBlank()) choosePlaytest(token, runId, "intent")
                resolveDeviceId()
            }
        }

        private fun playtestIntentAllowed(): Boolean {
            val info = app.applicationInfo
            if (info != null && info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
            val meta = try {
                app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
            } catch (t: Throwable) {
                null
            } ?: info?.metaData
            return meta?.getBoolean(META_ALLOW_PLAYTEST_INTENT, false) == true
        }

        // --- Lifecycle, worker thread -------------------------------------------

        private fun foreground(now: Long) {
            inForeground = true
            if (!foregrounded) {
                firstForeground(now)
                return
            }
            deleteMarker()
            val backgroundedAt = backgroundedAtMs
            backgroundedAtMs = null
            if (backgroundedAt != null && now - backgroundedAt >= config.sessionTimeoutMs) {
                endSessionAfterBackground(backgroundedAt)
                queueGameStarted(now)
            } else if (backgroundedAt != null && config.trackPauseEvents) {
                core.track("game_resumed", emptyMap(), now / 1000L)
            }
            if (core.isActive) scheduleTick()
            pump()
        }

        private fun firstForeground(now: Long) {
            core.setEnabled(effectiveEnabled())
            if (!playtestChosen) {
                val token = deps.env(ENV_PLAYTEST_TOKEN)
                if (!token.isNullOrBlank()) choosePlaytest(token, deps.env(ENV_PLAYTEST_RUN_ID), "environment")
            }
            resolveDeviceId()
            reportExitFromLastLaunch(now)
            foregrounded = true
            queueGameStarted(now)
            if (core.isActive) scheduleTick()
            pump()
        }

        private fun background(now: Long) {
            inForeground = false
            if (!foregrounded) return
            backgroundedAtMs = now
            if (config.trackPauseEvents) core.track("game_paused", emptyMap(), now / 1000L)
            writeMarker(now)
            if (config.flushOnBackground) pump()
        }

        /** The app stayed in the background past the timeout: close the old session. */
        private fun endSessionAfterBackground(backgroundedAt: Long) {
            if (config.trackLifecycleEvents && !exitedQueued && core.isActive && core.isSessionValid()) {
                exitedQueued = true
                core.track("game_exited", emptyMap(), backgroundedAt / 1000L)
                drainBatchesNow()
            }
            core.invalidateSession()
            startedQueued = false
            exitedQueued = false
        }

        private fun queueGameStarted(now: Long) {
            if (!config.trackLifecycleEvents || startedQueued || !foregrounded || !core.isActive) return
            startedQueued = true
            core.track("game_started", emptyMap(), now / 1000L)
        }

        private fun applyEnabled() {
            core.setEnabled(effectiveEnabled())
            if (core.isActive) {
                if (foregrounded) {
                    queueGameStarted(clock())
                    scheduleTick()
                    pump()
                }
            } else {
                stopTick()
            }
        }

        private fun choosePlaytest(token: String, runId: String?, source: String) {
            if (playtestChosen || token.isBlank()) return
            playtestChosen = true
            isPlaytest = true
            playtestToken = token.trim()
            if (runId.isNullOrBlank()) {
                logOnce("playtest_no_run", Log.ERROR, "playtest token without a run id ($source); not running as a real player, sending stopped for this run")
                core.stopForRun(RavensightReason.SESSION_FAILED)
                return
            }
            deviceId = "pt-${runId.trim()}"
            core.invalidateSession()
            core.setEnabled(effectiveEnabled())
            verbose("playtest run ${runId.trim()} from $source")
        }

        private fun resolveDeviceId() {
            if (deviceId != null || isPlaytest) return
            val saved = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotEmpty() }
            deviceId = saved ?: newDeviceId().also { prefs.edit().putString(KEY_DEVICE_ID, it).commit() }
        }

        // --- The exit marker ----------------------------------------------------

        private fun writeMarker(backgroundedAt: Long) {
            if (isPlaytest || !config.trackLifecycleEvents || !core.isActive || !core.isSessionValid()) return
            val token = core.sessionToken ?: return
            prefs.edit()
                .putString(KEY_MARKER_TOKEN, token)
                .putLong(KEY_MARKER_EXPIRES_AT, core.sessionExpiresAtMs)
                .putLong(KEY_MARKER_BACKGROUNDED_AT, backgroundedAt)
                .commit()
        }

        private fun deleteMarker() {
            if (!prefs.contains(KEY_MARKER_TOKEN)) return
            prefs.edit()
                .remove(KEY_MARKER_TOKEN)
                .remove(KEY_MARKER_EXPIRES_AT)
                .remove(KEY_MARKER_BACKGROUNDED_AT)
                .commit()
        }

        /**
         * A marker left by the last launch means the app was killed in the
         * background: report that session's exit, once, before anything else.
         */
        private fun reportExitFromLastLaunch(now: Long) {
            val token = prefs.getString(KEY_MARKER_TOKEN, null)
            val expiresAt = prefs.getLong(KEY_MARKER_EXPIRES_AT, 0L)
            val backgroundedAt = prefs.getLong(KEY_MARKER_BACKGROUNDED_AT, 0L)
            if (token == null) return
            deleteMarker()
            if (token.isEmpty() || isPlaytest || !core.isActive || now >= expiresAt || backgroundedAt <= 0L) return
            val event = JSONObject()
                .put("event", "game_exited")
                .put("data", JSONObject())
                .put("timestamp", backgroundedAt / 1000L)
            val body = JSONObject().put("events", JSONArray().put(event))
            val result = transport.request("POST", "$apiUrl/track/batch", headers("X-Session-Token" to token), body.toString())
            verbose("last launch's game_exited answered HTTP ${result.status}")
        }

        // --- The driver loop, worker thread only ---------------------------------

        /**
         * One protocol step per worker message, so a setEnabled or opt-out
         * posted meanwhile runs before the next request.
         */
        private fun pump() {
            worker.remove(pumpRunnable)
            if (!foregrounded) return
            // An opt-out set from another thread wins before the next request,
            // even if its own worker message has not run yet.
            if (!effectiveEnabled()) core.setEnabled(false)
            when (val action = core.nextAction()) {
                is RavensightAction.Idle -> return
                is RavensightAction.WaitUntil -> {
                    val delay = (action.atMs - clock()).coerceAtLeast(1L)
                    worker.postDelayed(pumpRunnable, delay)
                    return
                }
                is RavensightAction.CheckSettings -> checkSettings()
                is RavensightAction.OpenSession -> openSession()
                is RavensightAction.SendBatch -> sendBatch(action.events)
            }
            worker.post(pumpRunnable)
        }

        /** Sends batches on the current session right now, without opening a new one. */
        private fun drainBatchesNow() {
            repeat(MAX_DRAIN_STEPS) {
                val action = core.nextAction() as? RavensightAction.SendBatch ?: return
                sendBatch(action.events)
            }
        }

        private fun headers(vararg pairs: Pair<String, String>): Map<String, String> {
            val map = HashMap<String, String>()
            for ((k, v) in pairs) map[k] = v
            playtestToken?.let { map["X-Ravensight-Playtest"] = it }
            return map
        }

        private fun checkSettings() {
            val result = transport.request("GET", "$apiUrl/settings", headers("X-API-Key" to config.ingestKey), null)
            val value = result.json?.takeIf { it.has("trackingEnabled") }?.optBoolean("trackingEnabled")
            val errorCode = result.errorCode("")
            val refusal = RavensightReason.forRefusal(result.status, errorCode)
            core.onSettingsResult(result.status, value, errorCode)
            when {
                refusal != null -> handleRefusal(refusal)
                !core.trackingEnabled -> handleRefusal(RavensightReason.TRACKING_DISABLED)
                result.status != 200 ->
                    verbose("settings check failed (HTTP ${result.status}), assuming tracking enabled")
            }
        }

        private fun openSession() {
            if (deviceId == null) resolveDeviceId()
            val id = deviceId ?: run {
                // Only a stopped playtest run has no id, and it never gets here.
                core.onSessionResult(0, null)
                return
            }
            val body = JSONObject()
                .put("deviceId", id)
                .put("gameVersion", gameVersion)
                .put("platform", "android")
            val result = transport.request("POST", "$apiUrl/session", headers("X-API-Key" to config.ingestKey), body.toString())
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
                    verbose("session ready")
                    if (!inForeground) backgroundedAtMs?.let { writeMarker(it) }
                    if (foregrounded && core.isActive) scheduleTick()
                    emit { it.onSessionReady() }
                }
                is SessionOutcome.RateLimited -> {
                    verbose("session creation rate limited (${outcome.reason})")
                    emit { it.onSessionFailed(outcome.reason) }
                }
                is SessionOutcome.Failed -> {
                    verbose("session creation failed (${outcome.reason}), will retry")
                    emit { it.onSessionFailed(outcome.reason) }
                }
                is SessionOutcome.Refused -> {
                    handleRefusal(outcome.reason)
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
            val result = transport.request("POST", "$apiUrl/track/batch", headers("X-Session-Token" to token), payload.toString())
            val errorCode = result.json?.optString("error")?.takeIf { it.isNotEmpty() }
            when (val outcome = core.onBatchResult(result.status, result.retryAfterSec, errorCode)) {
                is BatchOutcome.Flushed -> {
                    verbose("flushed ${outcome.count} events")
                    emit { it.onEventsFlushed(outcome.count) }
                }
                is BatchOutcome.SessionExpired -> verbose("session invalid or expired, refreshing")
                is BatchOutcome.RateLimited -> {
                    verbose("track/batch rate limited (${outcome.reason})")
                    emit { it.onFlushFailed(outcome.reason) }
                }
                is BatchOutcome.BatchSplit ->
                    verbose("batch rejected, splitting to ${outcome.newLimit}")
                is BatchOutcome.EventRejected ->
                    logOnce("event_rejected", Log.WARN, "dropped an event the server rejected with HTTP 400 (counted as dropped)")
                is BatchOutcome.Failed -> {
                    verbose("batch flush failed (${outcome.reason}), will retry")
                    emit { it.onFlushFailed(outcome.reason) }
                }
                is BatchOutcome.Refused -> {
                    handleRefusal(outcome.reason)
                    emit { it.onFlushFailed(outcome.reason) }
                }
            }
        }

        /** A 401 or 403 that stops automatic sending: log once, tell the game. */
        private fun handleRefusal(reason: String) {
            when (reason) {
                RavensightReason.INVALID_KEY -> {
                    stopTick()
                    logOnce(reason, Log.WARN, "Ravensight: ingest key refused (invalid_key), sending stopped for this run. Check your gt_live_ key.")
                }
                RavensightReason.TRACKING_DISABLED -> {
                    stopTick()
                    if (loggedOnce.add(reason)) {
                        Log.i(TAG, "tracking disabled by the server for this game, sending stopped for this run")
                        emit { it.onTrackingDisabled("server") }
                    }
                }
                RavensightReason.PLAYTEST_JOB_CLOSED -> {
                    stopTick()
                    logOnce(reason, Log.ERROR, "playtest job closed (playtest_job_closed), sending stopped for this run")
                }
                else -> {
                    stopTick()
                    logOnce(reason, Log.ERROR, "playtest token refused (invalid_playtest_token), sending stopped for this run")
                }
            }
        }

        // --- Feedback and suggestions, worker thread only -------------------------

        private fun sendFeedback(message: String, category: String?, rating: Int) {
            if (!category.isNullOrEmpty() && category !in RavensightConfig.FEEDBACK_CATEGORIES) {
                Log.w(TAG, "feedback category must be one of ${RavensightConfig.FEEDBACK_CATEGORIES.joinToString(", ")}")
                emit { it.onFeedbackFailed("invalid_category") }
                return
            }
            if (rating != 0 && rating !in 1..5) {
                Log.w(TAG, "feedback rating must be 0 (omitted) or an integer from 1 to 5")
                emit { it.onFeedbackFailed("invalid_rating") }
                return
            }
            if (!core.isActive || core.keyRefused) {
                verbose("tracking is off, feedback not sent")
                emit { it.onFeedbackFailed("disabled") }
                return
            }
            if (message.isBlank()) {
                Log.w(TAG, "feedback message is empty")
                emit { it.onFeedbackFailed("empty_message") }
                return
            }
            val token = core.sessionToken?.takeIf { core.isSessionValid() }
            if (token == null) {
                verbose("no valid session yet, cannot submit feedback")
                emit { it.onFeedbackFailed("no_session") }
                core.requestSession()
                pump()
                return
            }

            val payload = JSONObject().put("message", message)
            if (!category.isNullOrEmpty()) payload.put("category", category)
            if (rating > 0) payload.put("rating", rating)

            val result = transport.request("POST", "$apiUrl/feedback", headers("X-Session-Token" to token), payload.toString())
            val errorCode = result.errorCode("http_${result.status}")
            when (result.status) {
                201 -> emit { it.onFeedbackSubmitted() }
                401 -> {
                    // The session expired or was revoked: open a new one for next time.
                    core.invalidateSession()
                    core.requestSession()
                    emit { it.onFeedbackFailed(errorCode) }
                    pump()
                }
                403 -> {
                    val reason = RavensightReason.forRefusal(403, errorCode) ?: RavensightReason.INVALID_KEY
                    core.onRefused(reason)
                    handleRefusal(reason)
                    emit { it.onFeedbackFailed(errorCode) }
                }
                else -> {
                    verbose("feedback submission failed (HTTP ${result.status})")
                    emit { it.onFeedbackFailed(errorCode) }
                }
            }
        }

        private fun requestSuggestions() {
            if (!foregrounded || !core.isActive || core.keyRefused) {
                emit { it.onSuggestionsReceived(emptyList()) }
                return
            }
            val result = transport.request("GET", "$apiUrl/agent/suggestions", headers("X-API-Key" to config.ingestKey), null)
            val suggestions: List<Any?> = if (result.status == 200) {
                val array = result.json?.optJSONArray("suggestions")
                @Suppress("UNCHECKED_CAST")
                if (array != null) RavensightJson.toPlain(array) as List<Any?> else emptyList()
            } else {
                RavensightReason.forRefusal(result.status, result.errorCode(""))?.let {
                    core.onRefused(it)
                    handleRefusal(it)
                }
                verbose("fetchSuggestions failed (HTTP ${result.status})")
                emptyList()
            }
            emit { it.onSuggestionsReceived(suggestions) }
        }

        // --- Helpers ------------------------------------------------------------

        private fun scheduleTick() {
            if (tickScheduled || config.flushIntervalMs <= 0) return
            tickScheduled = true
            worker.postDelayed(tickRunnable, config.flushIntervalMs)
        }

        private fun stopTick() {
            tickScheduled = false
            worker.remove(tickRunnable)
        }

        /** Runs [body] on the worker; nothing it throws can reach the looper. */
        private fun post(what: String, body: () -> Unit) {
            worker.post(Runnable { runSafely(what, body) })
        }

        private fun runSafely(what: String, body: () -> Unit) {
            try {
                body()
            } catch (t: Throwable) {
                Log.e(TAG, "$what failed inside the SDK, ignored", t)
            }
            try {
                publish()
            } catch (t: Throwable) {
                Log.e(TAG, "status update failed inside the SDK, ignored", t)
            }
        }

        /** Copies worker state to the mirrors other threads read, and reports a status change. */
        private fun publish() {
            serverAllows = core.trackingEnabled && core.stopReason == null
            active = serverAllows && effectiveEnabled()
            ready = core.isSessionValid()
            sessionToken = core.sessionToken
            val reason = core.reason
            statusReason = reason
            if (reason != lastPublishedReason) {
                lastPublishedReason = reason
                emit { it.onStatusChanged(reason) }
            }
        }

        private fun emit(call: (RavensightListener) -> Unit) {
            val target = listener ?: return
            mainPost(Runnable { call(target) })
        }

        private fun onMain(body: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) body() else mainPost(Runnable(body))
        }

        private fun logOnce(key: String, priority: Int, message: String) {
            if (loggedOnce.add(key)) Log.println(priority, TAG, message)
        }

        private fun verbose(message: String) {
            if (config.verboseLogging) Log.i(TAG, message)
        }

        /** Debug only: warns once per event and key about a reserved rs_ key Ravensight does not expect. */
        private fun checkReservedKeys(eventName: String, data: Map<String, Any?>) {
            if (!config.verboseLogging) return
            for ((key, value) in data) {
                if (!key.startsWith("rs_")) continue
                val message = when (key) {
                    "rs_kind" -> if (value is String && value in RavensightConfig.RS_KINDS) {
                        null
                    } else {
                        "rs_kind \"$value\" on $eventName is not one of ${RavensightConfig.RS_KINDS.joinToString(", ")}"
                    }
                    "rs_doctor" -> null
                    else -> "$key on $eventName uses the reserved rs_ prefix and is not read by Ravensight"
                }
                if (message != null && warnedReservedKeys.add("$eventName/$key")) Log.w(TAG, message)
            }
        }

        private fun appVersionName(): String = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        internal fun shutdownForTest() {
            (app as? Application)?.unregisterActivityLifecycleCallbacks(this)
            (worker as? HandlerWorker)?.quit()
        }
    }

    private const val MAX_DRAIN_STEPS = 20
}

/** Where the SDK's worker messages run. A HandlerThread in production, a manual queue in tests. */
internal interface RavensightWorker {
    fun post(r: Runnable)
    fun postDelayed(r: Runnable, delayMs: Long)
    fun remove(r: Runnable)
}

internal class HandlerWorker : RavensightWorker {
    private val thread = HandlerThread("Ravensight").apply { start() }
    private val handler = Handler(thread.looper)
    override fun post(r: Runnable) {
        handler.post(r)
    }
    override fun postDelayed(r: Runnable, delayMs: Long) {
        handler.postDelayed(r, delayMs)
    }
    override fun remove(r: Runnable) {
        handler.removeCallbacks(r)
    }
    fun quit() {
        thread.quitSafely()
    }
}

/** Seams for the unit tests. Production uses the defaults. */
internal class RavensightDeps(
    val transport: RavensightTransport? = null,
    val clockMs: () -> Long = { System.currentTimeMillis() },
    val worker: RavensightWorker? = null,
    val mainPost: ((Runnable) -> Unit)? = null,
    val env: (String) -> String? = { System.getenv(it) },
    val observeProcessLifecycle: Boolean = true,
    val registerActivityCallbacks: Boolean = true,
)
