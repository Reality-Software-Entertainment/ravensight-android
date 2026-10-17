package com.realityse.ravensight

import java.util.Random
import kotlin.math.ceil

/**
 * The typed event contract: reserved rs_ keys that turn an ordinary track
 * call into a purchase, a resource flow, a progression step, an error or a
 * health sample. Everything here only builds the property set. Queueing,
 * batching, retries and opt-out are the generic track path's, unchanged.
 * Nothing validates beyond cutting long text: a wrong value is flagged by the
 * server with rs_invalid and never throws here.
 */
internal object TypedEvents {
    const val MAX_MESSAGE_CHARS = 200
    const val MAX_STACK_CHARS = 2000

    /** Every rs_ key the platform reads. rs_invalid is written by the server only and is not listed. */
    val KEYS: Set<String> = setOf(
        "rs_kind", "rs_doctor",
        "rs_amount", "rs_currency", "rs_item_type", "rs_item_id", "rs_cart", "rs_transaction_id",
        "rs_flow", "rs_resource",
        "rs_status", "rs_p1", "rs_p2", "rs_p3", "rs_score",
        "rs_severity", "rs_message", "rs_stack",
        "rs_fps_median", "rs_fps_p10", "rs_memory_mb", "rs_boot_ms",
    )

    /** Caller keys first, so the reserved keys always win. */
    private fun merge(extra: Map<String, Any?>?, reserved: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (extra != null) out.putAll(extra)
        out.putAll(reserved)
        return out
    }

    private fun cut(text: String, max: Int): String =
        if (text.length <= max) text else text.codePoints().limit(max.toLong()).toArray()
            .let { String(it, 0, it.size) }

    fun purchase(
        amount: Any?,
        currency: String,
        itemType: String,
        itemId: String,
        cart: String?,
        transactionId: String?,
        extra: Map<String, Any?>?,
    ): Map<String, Any?> {
        val reserved = LinkedHashMap<String, Any?>()
        reserved["rs_kind"] = "purchase"
        reserved["rs_amount"] = amount
        reserved["rs_currency"] = currency.uppercase()
        reserved["rs_item_type"] = itemType
        reserved["rs_item_id"] = itemId
        if (cart != null) reserved["rs_cart"] = cart
        if (transactionId != null) reserved["rs_transaction_id"] = transactionId
        return merge(extra, reserved)
    }

    fun resource(
        flow: String,
        resource: String,
        amount: Number,
        itemType: String?,
        itemId: String?,
        extra: Map<String, Any?>?,
    ): Map<String, Any?> {
        val reserved = LinkedHashMap<String, Any?>()
        reserved["rs_kind"] = "resource"
        reserved["rs_flow"] = flow
        reserved["rs_resource"] = resource
        reserved["rs_amount"] = amount
        if (itemType != null) reserved["rs_item_type"] = itemType
        if (itemId != null) reserved["rs_item_id"] = itemId
        return merge(extra, reserved)
    }

    fun progression(
        status: String,
        p1: String,
        p2: String?,
        p3: String?,
        score: Number?,
        extra: Map<String, Any?>?,
    ): Map<String, Any?> {
        val reserved = LinkedHashMap<String, Any?>()
        reserved["rs_kind"] = "progression"
        reserved["rs_status"] = status
        reserved["rs_p1"] = p1
        if (p2 != null) reserved["rs_p2"] = p2
        if (p3 != null) reserved["rs_p3"] = p3
        if (score != null) reserved["rs_score"] = score
        return merge(extra, reserved)
    }

    fun error(severity: String, message: String, stack: String?, extra: Map<String, Any?>?): Map<String, Any?> {
        val reserved = LinkedHashMap<String, Any?>()
        reserved["rs_kind"] = "error"
        reserved["rs_severity"] = severity
        reserved["rs_message"] = cut(message, MAX_MESSAGE_CHARS)
        if (stack != null) reserved["rs_stack"] = cut(stack, MAX_STACK_CHARS)
        return merge(extra, reserved)
    }

    fun health(summary: HealthSummary, memoryMb: Double?, bootMs: Long?): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["rs_kind"] = "health"
        out["rs_fps_median"] = summary.fpsMedian
        out["rs_fps_p10"] = summary.fpsP10
        if (memoryMb != null) out["rs_memory_mb"] = memoryMb
        if (bootMs != null) out["rs_boot_ms"] = bootMs
        return out
    }
}

internal data class HealthSummary(val fpsMedian: Double, val fpsP10: Double)

/**
 * Frames per second, sampled once per second. Feed it every frame's
 * timestamp (Choreographer's frameTimeNanos); it counts frames and records
 * one fps sample per elapsed second into a reservoir of at most
 * [MAX_SAMPLES], so a long session cannot grow it. Thread safe.
 */
internal class FrameSampler(private val random: Random = Random()) {
    private val samples = DoubleArray(MAX_SAMPLES)
    private var stored = 0
    private var seen = 0L
    private var windowStartNanos = -1L
    private var frames = 0

    @Synchronized
    fun onFrame(frameTimeNanos: Long) {
        if (windowStartNanos < 0) {
            windowStartNanos = frameTimeNanos
            frames = 0
            return
        }
        frames++
        val elapsed = frameTimeNanos - windowStartNanos
        if (elapsed >= NANOS_PER_SECOND) {
            add(frames * NANOS_PER_SECOND.toDouble() / elapsed)
            windowStartNanos = frameTimeNanos
            frames = 0
        }
    }

    /** Forgets the open one-second window, e.g. after the app was in the background. */
    @Synchronized
    fun resetWindow() {
        windowStartNanos = -1L
        frames = 0
    }

    @Synchronized
    fun clear() {
        stored = 0
        seen = 0
        resetWindow()
    }

    @get:Synchronized
    val sampleCount: Int get() = stored

    /** Median and 10th percentile fps, or null when no sample was ever taken. */
    @Synchronized
    fun summary(): HealthSummary? {
        if (stored == 0) return null
        val sorted = samples.copyOf(stored).also { it.sort() }
        val mid = stored / 2
        val median = if (stored % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        // Nearest rank: the smallest value with at least 10 percent of samples at or below it.
        val rank = ceil(0.1 * stored).toInt().coerceIn(1, stored)
        return HealthSummary(median, sorted[rank - 1])
    }

    private fun add(fps: Double) {
        seen++
        if (stored < MAX_SAMPLES) {
            samples[stored++] = fps
        } else {
            // Reservoir sampling: every sample seen so far is equally likely to be kept.
            val slot = (random.nextDouble() * seen).toLong()
            if (slot < MAX_SAMPLES) samples[slot.toInt()] = fps
        }
    }

    companion object {
        const val MAX_SAMPLES = 600
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
