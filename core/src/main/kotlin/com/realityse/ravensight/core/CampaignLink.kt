package com.realityse.ravensight.core

/**
 * The campaign link: the rs_link id a game passes from an App Link, a deep
 * link or an install referrer it already handles. It waits in memory, goes
 * once as campaignLink on the next POST /session the SDK opens for its own
 * player, and is dropped only when a session opens. Never saved. Thread safe.
 */
class CampaignLinkState {
    private var pending: String? = null

    /** The link waiting for a session, lower case, or null. */
    val pendingLink: String?
        @Synchronized get() = pending

    /**
     * Makes [id] the waiting link, replacing any earlier one, and returns
     * true when it is a link id. Anything else returns false and changes
     * nothing.
     */
    @Synchronized
    fun set(id: String?): Boolean {
        pending = valid(id) ?: return false
        return true
    }

    /** The link a session request leaving now carries: none for a playtest run. */
    @Synchronized
    fun forSession(playtest: Boolean): String? = if (playtest) null else pending

    /**
     * The session request that carried [sent] was answered. [opened] (201
     * with a token) drops that link, unless a different one was set since;
     * any other answer keeps it for the next attempt.
     */
    @Synchronized
    fun onSessionAnswer(sent: String?, opened: Boolean) {
        if (opened && sent != null && pending == sent) pending = null
    }

    companion object {
        /** The length of a link id. */
        const val LENGTH: Int = 24

        /** A link id (24 hex characters, any case) lower case, or null for anything else. */
        @JvmStatic
        fun valid(id: String?): String? {
            if (id == null || id.length != LENGTH) return null
            if (!id.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return id.lowercase()
        }
    }
}
