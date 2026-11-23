package com.realityse.ravensight.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What is kept beside the device id after identify: never the account id. */
data class StoredIdentity(val salt: String, val accountHash: String)

/** Identify's pure rules: validation, the salted hash and what an answer means. */
object RavensightIdentity {
    /** The longest account id accepted, in UTF-8 bytes. */
    const val MAX_BYTES: Int = 256

    /**
     * The refusal code for an account id, or null when it may be used. The id
     * is checked exactly as given: never trimmed or normalised.
     */
    fun refusal(accountId: String?): String? = when {
        accountId == null -> "not_text"
        accountId.isEmpty() -> "empty"
        accountId.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> "too_long"
        accountId.contains('@') -> "looks_like_email"
        else -> null
    }

    /**
     * Lower case hex HMAC-SHA256 keyed with the UTF-8 bytes of the salt string
     * exactly as the session answer gave it (not hex decoded), over the UTF-8
     * bytes of the account id.
     */
    fun accountHash(accountId: String, salt: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return RavensightEventIds.hex(mac.doFinal(accountId.toByteArray(Charsets.UTF_8)))
    }

    enum class Outcome {
        /** Done for this session (200). */
        DONE,
        /** Not again this session; the next session tries again. */
        NEXT_SESSION,
        /** Not again this launch (404, or a hash the server calls invalid). */
        STOP_FOR_LAUNCH,
    }

    fun outcome(status: Int, error: String?): Outcome = when {
        status in 200..299 -> Outcome.DONE
        status == 404 -> Outcome.STOP_FOR_LAUNCH
        status == 400 && error == "invalid_account_hash" -> Outcome.STOP_FOR_LAUNCH
        else -> Outcome.NEXT_SESSION
    }
}

/** How an account id relates to the identity already stored or held. */
enum class IdentifyRelation {
    /** The same account, already known under this launch's salt: nothing to do. */
    SAME,
    /** Matches the stored identity, but the salt is not known yet: hold it. */
    HOLD,
    /** Another account than the stored or held one: reset first. */
    OTHER,
    /** Nothing stored or held. */
    NEW,
}

/** What the driver should do for identify now. */
sealed class IdentifyStep {
    object None : IdentifyStep()
    /** An identity waits for a session (or for the salt a session brings). */
    object NeedSession : IdentifyStep()
    class Send(val accountHash: String, val token: String, val generation: Long) : IdentifyStep()
}

/**
 * Identify's state for one launch. Thread safe. The driver owns storage
 * (through [persist]), the network and timing.
 */
class IdentifyState(stored: StoredIdentity?, private val persist: (StoredIdentity?) -> Unit) {
    private var identity: StoredIdentity? = stored
    /** A valid account id waiting for a salt or for consent. Memory only. */
    private var heldAccountId: String? = null
    /** The salt this launch's session answer carried. */
    private var launchSalt: String? = null
    /** A session answer carried no salt: unavailable this launch. */
    private var saltMissing = false
    /** A 404 or invalid hash answer: nothing more is sent this launch. */
    private var stopped = false
    /** The session token identify was last sent under (any answer). */
    private var identifiedSession: String? = null
    /** Bumped by [forget]: an answer from before is stale. */
    private var generation = 0L
    /** no_session answers in a row: a second one ends identify for the launch. */
    private var noSessionStreak = 0
    /** Two no_session answers in a row: stopped until [forget] (a sign-out). */
    private var noSessionStopped = false

    val stored: StoredIdentity? @Synchronized get() = identity
    val isHeld: Boolean @Synchronized get() = heldAccountId != null
    val hasIdentity: Boolean @Synchronized get() = identity != null || heldAccountId != null

    /** True when [accountId] is the identity already stored or held. */
    @Synchronized
    fun isSame(accountId: String): Boolean =
        heldAccountId == accountId || identity?.let { RavensightIdentity.accountHash(accountId, it.salt) == it.accountHash } == true

    /**
     * How [accountId] relates to what is stored or held. Before this launch's
     * salt is known a match with the stored identity is [IdentifyRelation.HOLD]:
     * the salt that arrives may differ, and the id is hashed under it.
     */
    @Synchronized
    fun relation(accountId: String): IdentifyRelation {
        if (heldAccountId == accountId) return IdentifyRelation.SAME
        val current = identity
        if (current != null && RavensightIdentity.accountHash(accountId, current.salt) == current.accountHash) {
            return if (launchSalt != null) IdentifyRelation.SAME else IdentifyRelation.HOLD
        }
        return if (current != null || heldAccountId != null) IdentifyRelation.OTHER else IdentifyRelation.NEW
    }

    /**
     * Takes a valid account id (the caller runs a reset first for a different
     * account). Hashed and stored at once when [canStore] and the salt is
     * known, otherwise held in memory. False when identify is unavailable
     * this launch (nothing kept).
     */
    @Synchronized
    fun accept(accountId: String, canStore: Boolean): Boolean {
        if (saltMissing) return false
        val salt = launchSalt
        if (canStore && salt != null) {
            store(StoredIdentity(salt, RavensightIdentity.accountHash(accountId, salt)))
            heldAccountId = null
        } else {
            heldAccountId = accountId
        }
        return true
    }

    /** Drops the stored identity and any held id. Returns whether there was one. */
    @Synchronized
    fun forget(): Boolean {
        val had = identity != null || heldAccountId != null
        if (identity != null) store(null)
        heldAccountId = null
        identifiedSession = null
        noSessionStreak = 0
        noSessionStopped = false
        generation++
        return had
    }

    /** A session answer arrived carrying [salt] (null: no identify.salt). */
    @Synchronized
    fun onSessionAnswer(salt: String?) {
        if (salt.isNullOrEmpty()) {
            saltMissing = true
            launchSalt = null
            heldAccountId = null
            return
        }
        launchSalt = salt
        val current = identity
        // Made under another game's salt: deleted, never sent.
        if (current != null && current.salt != salt) store(null)
    }

    /**
     * The next identify step for the current valid session [token] (null when
     * none). [canStore] is false without a key, before consent and in a
     * playtest run. A [IdentifyStep.Send] marks the session: whatever the
     * answer, it is not sent again in that session.
     */
    @Synchronized
    fun next(token: String?, canStore: Boolean): IdentifyStep {
        if (saltMissing) heldAccountId = null
        val held = heldAccountId
        val salt = launchSalt
        if (canStore && held != null && salt != null) {
            store(StoredIdentity(salt, RavensightIdentity.accountHash(held, salt)))
            heldAccountId = null
        }
        if (stopped || noSessionStopped || saltMissing || (identity == null && heldAccountId == null)) return IdentifyStep.None
        val current = identity
        if (token == null || salt == null || current == null) return IdentifyStep.NeedSession
        if (identifiedSession == token) return IdentifyStep.None
        identifiedSession = token
        return IdentifyStep.Send(current.accountHash, token, generation)
    }

    /** An identify answer. Null when it belongs to an identity since forgotten. */
    @Synchronized
    fun onAnswer(generation: Long, status: Int, error: String?): RavensightIdentity.Outcome? {
        if (generation != this.generation) return null
        val outcome = RavensightIdentity.outcome(status, error)
        if (status == 400 && error == "no_session") {
            // Identify again after the next session opens, once.
            noSessionStreak++
            if (noSessionStreak >= 2) {
                noSessionStopped = true
                return RavensightIdentity.Outcome.STOP_FOR_LAUNCH
            }
        } else {
            noSessionStreak = 0
        }
        if (outcome == RavensightIdentity.Outcome.STOP_FOR_LAUNCH) stopped = true
        return outcome
    }

    private fun store(value: StoredIdentity?) {
        identity = value
        persist(value)
    }
}
