package com.realityse.ravensight

import com.realityse.ravensight.core.RavensightCoreConfig

/**
 * Configuration for [Ravensight.initialize]. Only the ingest key is required.
 */
class RavensightConfig(
    /**
     * Your game's PUBLISHABLE ingest key (format: gt_live_...), copied from
     * the Ravensight dashboard. Safe to ship inside your APK: it can only
     * open sessions and read the tracking kill switch. It cannot read
     * analytics, read feedback or touch your account.
     */
    val ingestKey: String,
    /**
     * Base URL. Only change this if you self host. "/api/v1" is appended
     * when omitted, so both "https://api.ravensight.io" and
     * "https://api.ravensight.io/api/v1" work.
     */
    val apiUrl: String = DEFAULT_API_URL,
    /** Reported as the client's game version. Empty uses the app's versionName. */
    val gameVersion: String = "",
    /** Offline queue cap. Oldest events are dropped first past this. */
    val maxQueueSize: Int = RavensightCoreConfig.DEFAULT_QUEUE_CAP,
    /** Flush timer period. 0 disables the timer, flushing only on demand and on lifecycle stops. */
    val flushIntervalMs: Long = 5_000L,
    /** Per request connect and read timeout. */
    val requestTimeoutMs: Int = 15_000,
    /**
     * When true (the default) the SDK tracks game_started at initialize,
     * game_paused when the app leaves the foreground and game_resumed when it
     * returns. There is no reliable process exit signal on Android, so no
     * game_exited event is sent.
     */
    val trackLifecycleEvents: Boolean = true,
    /** When true (the default) the SDK flushes queued events every time the app leaves the foreground. */
    val flushOnBackground: Boolean = true,
    /** Logs SDK activity to Logcat under the "Ravensight" tag. */
    val verboseLogging: Boolean = false,
    /** Start opted out by setting this false, then call [Ravensight.setEnabled]. */
    val enabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_API_URL: String = "https://api.ravensight.io/api/v1"

        /** Accepts a bare host or a full /api/v1 base and always returns the versioned base. */
        fun normalizeApiUrl(url: String): String {
            var raw = url.trim().trimEnd('/')
            if (raw.isEmpty()) raw = DEFAULT_API_URL.trimEnd('/')
            return if (raw.endsWith("/api/v1")) raw else "$raw/api/v1"
        }
    }
}

/**
 * Optional observer for SDK activity, mirroring the Godot SDK's signals.
 * Every method has a default empty body; override only what you need.
 * Callbacks are delivered on the main thread.
 */
interface RavensightListener {
    /** A session token has been issued and events are flowing. */
    fun onSessionReady() {}

    /** Session creation failed outright (it will be retried automatically). */
    fun onSessionFailed(reason: String) {}

    /** Emitted once, on boot, if the server side kill switch has tracking off. */
    fun onTrackingDisabled(source: String) {}

    /** A batch of events was accepted by the server. */
    fun onEventsFlushed(count: Int) {}

    /** A batch flush attempt failed (it will be retried automatically). */
    fun onFlushFailed(reason: String) {}

    /** A feedback submission was accepted. */
    fun onFeedbackSubmitted() {}

    /** A feedback submission failed. */
    fun onFeedbackFailed(reason: String) {}

    /**
     * EXPERIMENTAL: the result of [Ravensight.fetchSuggestions]. Suggestions
     * are AI generated design hints and may change shape over time.
     */
    fun onSuggestionsReceived(suggestions: List<Any?>) {}
}
