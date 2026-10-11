package com.realityse.ravensight

import com.realityse.ravensight.core.RavensightCoreConfig

/**
 * Configuration for [Ravensight.initialize]. Only the ingest key is required.
 */
class RavensightConfig @JvmOverloads constructor(
    /**
     * Your game's PUBLISHABLE ingest key (format: gt_live_...), copied from
     * the Ravensight dashboard. Safe to ship inside your APK: it can open
     * sessions, read the tracking kill switch and read this game's design
     * suggestions. It cannot read analytics, read feedback or touch your
     * account.
     */
    val ingestKey: String,
    /**
     * Base URL. Only change this if you self host. Whitespace and trailing
     * slashes are trimmed and "/api/v1" is appended when omitted, so both
     * "https://api.ravensight.io" and "https://api.ravensight.io/api/v1/"
     * work. Empty means the default.
     */
    val apiUrl: String = DEFAULT_API_URL,
    /** Reported as the client's game version. Empty uses the app's versionName. */
    val gameVersion: String = "",
    /** Offline queue cap. Oldest events are dropped first past this. */
    val maxQueueSize: Int = RavensightCoreConfig.DEFAULT_QUEUE_CAP,
    /** Flush timer period. 0 disables the timer, flushing only on demand, at 50 queued and on background. */
    val flushIntervalMs: Long = 5_000L,
    /** Per request connect and read timeout. */
    val requestTimeoutMs: Int = 15_000,
    /**
     * When true (the default) the SDK sends the two automatic events:
     * game_started when a session begins in the foreground, and game_exited,
     * stamped with the time the app went to the background, once the app
     * has stayed in the background for [sessionTimeoutMs] (sent on return,
     * or on the next cold launch if the process was killed).
     */
    val trackLifecycleEvents: Boolean = true,
    /**
     * Opt-in: also send game_paused when the app goes to the background and
     * game_resumed when it returns within [sessionTimeoutMs]. Off by default.
     */
    val trackPauseEvents: Boolean = false,
    /**
     * How long the app may stay in the background before the session ends.
     * Default 30 minutes. Shorter backgrounds keep the same session.
     */
    val sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS,
    /** When true (the default) the SDK flushes queued events every time the app leaves the foreground. */
    val flushOnBackground: Boolean = true,
    /**
     * Logs SDK activity to Logcat under the "Ravensight" tag, and warns about
     * an rs_kind outside [RS_KINDS] or an unknown rs_ key.
     */
    val verboseLogging: Boolean = false,
    /**
     * False starts the SDK switched off: no request of any kind until
     * [Ravensight.setEnabled] turns it on. Never persisted. The player's own
     * choice is [Ravensight.setTrackingEnabled], which is saved, and a saved
     * opt-out wins over enabled = true.
     */
    val enabled: Boolean = true,
    /**
     * Ravensight Playtest run token. Normally left null: the SDK reads it
     * from the launch intent extra "ravensight_playtest_token" or the
     * RAVENSIGHT_PLAYTEST_TOKEN environment variable.
     */
    val playtestToken: String? = null,
    /** Ravensight Playtest run id, paired with [playtestToken]. */
    val playtestRunId: String? = null,
) {
    companion object {
        /** The bare host; "/api/v1" is appended by [normalizeApiUrl]. */
        const val DEFAULT_API_URL: String = "https://api.ravensight.io"

        const val DEFAULT_SESSION_TIMEOUT_MS: Long = 1_800_000L

        /** The six feedback categories the server accepts. */
        @JvmField
        val FEEDBACK_CATEGORIES: List<String> =
            listOf("bug", "suggestion", "complaint", "praise", "playtest", "other")

        /** Values the reserved rs_kind event property accepts. */
        @JvmField
        val RS_KINDS: List<String> = listOf("level_start", "level_complete", "death", "none")

        /**
         * Trims whitespace, strips every trailing "/", strips a trailing
         * "/api/v1", then appends "/api/v1". Empty means the default host.
         */
        @JvmStatic
        fun normalizeApiUrl(url: String): String {
            var raw = url.trim().trimEnd('/')
            if (raw.endsWith("/api/v1")) raw = raw.removeSuffix("/api/v1").trimEnd('/')
            if (raw.isEmpty()) raw = DEFAULT_API_URL
            return "$raw/api/v1"
        }
    }
}

/**
 * Optional observer for SDK activity. Every method has a default empty body;
 * override only what you need. Callbacks are delivered on the main thread.
 */
interface RavensightListener {
    /** A session token has been issued and events are flowing. */
    fun onSessionReady() {}

    /** Session creation failed. Retried automatically unless the reason stops the run. */
    fun onSessionFailed(reason: String) {}

    /**
     * Tracking was switched off: "server" when the kill switch is on for
     * your game, "player" after a saved opt-out.
     */
    fun onTrackingDisabled(source: String) {}

    /**
     * The SDK's status changed. [reason] is null when delivery is healthy,
     * otherwise one of "disabled", "invalid_key", "tracking_disabled",
     * "offline", "rate_limited", "session_failed", "playtest_job_closed".
     * The same value is readable at any time as [Ravensight.statusReason].
     */
    fun onStatusChanged(reason: String?) {}

    /** A batch of events was accepted by the server. */
    fun onEventsFlushed(count: Int) {}

    /** A batch flush attempt failed (it will be retried automatically). */
    fun onFlushFailed(reason: String) {}

    /** A feedback submission was accepted. */
    fun onFeedbackSubmitted() {}

    /**
     * A feedback submission failed. Local failures, with no request made:
     * "invalid_category", "invalid_rating", "empty_message", "disabled",
     * "no_session" (sent too early; a session is opened, try again).
     */
    fun onFeedbackFailed(reason: String) {}

    /**
     * EXPERIMENTAL: the result of [Ravensight.fetchSuggestions]. Suggestions
     * are AI generated design hints and may change shape over time. Always
     * delivered, as an empty list when tracking is off or the request failed.
     */
    fun onSuggestionsReceived(suggestions: List<Any?>) {}
}
