package com.realityse.ravensight.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Mints the `rs_event_id` every event carries inside its data, so the server
 * can recognise a resend of an event it already has.
 *
 * Shape: `<prefix>-<start>-<nonce>-<counter>`. The prefix is the first 16
 * characters of the device id (only letters, digits, `_` and `-` are kept),
 * start is the SDK's start time in Unix seconds as hex, the nonce is 8 hex
 * characters from a secure random source minted once per launch, and the
 * counter counts up from 1. Always matches [PATTERN].
 *
 * The id is written into the event's data when the event is queued, so a
 * retry of the same batch carries the same ids. Thread safe: the counter is
 * atomic, so the caller's thread and the worker can both stamp.
 */
class RavensightEventIds(
    /** 8 hex characters from a secure random source, once per launch. */
    val nonce: String,
    startSec: Long,
) {
    private val start = startSec.coerceAtLeast(0L).toString(16)
    private val counter = AtomicLong(0L)

    /** The next id, under [prefix] (see [prefixFor]). */
    fun next(prefix: String): String {
        val n = counter.incrementAndGet()
        return "${prefixFor(prefix)}-$start-$nonce-$n".take(MAX_LENGTH)
    }

    companion object {
        /** The shape the server accepts. */
        const val PATTERN: String = "^[A-Za-z0-9_-]{1,64}$"
        const val MAX_LENGTH: Int = 64
        const val PREFIX_LENGTH: Int = 16
        private val REGEX = Regex(PATTERN)

        /** The first 16 characters of [deviceId], keeping only the characters the shape allows. */
        @JvmStatic
        fun prefixFor(deviceId: String): String =
            deviceId.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
                .take(PREFIX_LENGTH)

        /** True when [id] matches the shape the server accepts. */
        @JvmStatic
        fun isValid(id: String): Boolean = REGEX.matches(id)

        /** [bytes] as lowercase hex. */
        @JvmStatic
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Limits shared with the server and every Ravensight SDK. */
object RavensightLimits {
    /** The key every event's data carries for deduplication. */
    const val EVENT_ID_KEY: String = "rs_event_id"

    /** An event whose data is larger than this as JSON is dropped before it is queued, never sent. */
    const val MAX_EVENT_BYTES: Int = 8192

    /** The longest distribution label the server accepts. */
    const val MAX_DISTRIBUTION_LENGTH: Int = 40

    /**
     * A distribution label the server will take: trimmed, 1 to 40
     * characters, no control characters. Anything else is null.
     */
    @JvmStatic
    fun validDistribution(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_DISTRIBUTION_LENGTH) return null
        if (trimmed.any { it.code < 0x20 || it.code == 0x7f }) return null
        return trimmed
    }
}
