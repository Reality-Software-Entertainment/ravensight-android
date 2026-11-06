package com.realityse.ravensight.core

/** The device limits from the error capture contract. Over a cap is dropped silently. */
object ErrorCaps {
    /** Automatic captures per session. */
    const val PER_SESSION: Int = 30

    /** Captures per local dedupe key (type plus message template plus first frame) per session. */
    const val PER_KEY: Int = 3

    /** Captures that carry a trail, per session. */
    const val TRAILS: Int = 10

    /** Token bucket size and refill, per minute. */
    const val PER_MINUTE: Int = 6

    /** Captures held in memory before the game's switch is known. */
    const val BOOT_HOLD: Int = 5

    const val MAX_MESSAGE_CHARS: Int = 200
    const val MAX_STACK_CHARS: Int = 2000
    const val MAX_TYPE_CHARS: Int = 100
    const val MAX_TRAIL_CHARS: Int = 1500
    const val MAX_TRAIL_LINE_CHARS: Int = 120
    const val TRAIL_LINES: Int = 20
}

/**
 * One automatic capture, already scrubbed and cut. [atMs] is when it
 * happened (Unix milliseconds), which may be in an earlier launch.
 */
class AutoError(
    val source: String,
    val severity: String,
    val message: String,
    val stack: String? = null,
    val type: String? = null,
    val trail: String? = null,
    val atMs: Long,
) {
    companion object {
        /** Scrubs and cuts every field. Text is never sent unscrubbed from an automatic capture. */
        @JvmStatic
        fun of(
            source: String,
            severity: String,
            message: String?,
            stack: String? = null,
            type: String? = null,
            trail: String? = null,
            atMs: Long,
        ): AutoError {
            val msg = ErrorScrub.clean(message.orEmpty().trim(), ErrorCaps.MAX_MESSAGE_CHARS)
            return AutoError(
                source = source,
                severity = severity,
                message = msg.ifEmpty { type?.let { ErrorScrub.clean(it, ErrorCaps.MAX_MESSAGE_CHARS) } ?: "(no message)" },
                stack = stack?.let { ErrorScrub.clean(it, ErrorCaps.MAX_STACK_CHARS) }?.takeIf { it.isNotBlank() },
                type = type?.let { ErrorScrub.clean(it, ErrorCaps.MAX_TYPE_CHARS) }?.takeIf { it.isNotBlank() },
                trail = trail?.let { ErrorScrub.clean(it, ErrorCaps.MAX_TRAIL_CHARS) }?.takeIf { it.isNotBlank() },
                atMs = atMs,
            )
        }

        /**
         * A JVM crash: the throwable's simple class name as the type, its
         * message (or the type when it has none), and the stack without the
         * header line, "Caused by" sections included.
         */
        @JvmStatic
        fun crash(t: Throwable, atMs: Long, trail: String? = null): AutoError {
            val type = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
            val full = try {
                t.stackTraceToString()
            } catch (_: Throwable) {
                ""
            }
            // Only the head is ever sent, so do not scrub a huge trace.
            val stack = full.substringAfter('\n', "").take(ErrorCaps.MAX_STACK_CHARS * 2)
            return of(EventSource.CRASH, Severity.CRITICAL, t.message, stack, type, trail, atMs)
        }
    }

    /** The local dedupe key: type, message template and first in-app frame. */
    val dedupeKey: String
        get() = (type ?: "") + "\u0000" + ErrorScrub.messageTemplate(message) + "\u0000" + ErrorScrub.firstFrame(stack)
}

object EventSource {
    const val CRASH: String = "crash"
}

object Severity {
    const val CRITICAL: String = "critical"
}

/**
 * A process exit the platform recorded (ApplicationExitInfo on API 30 and
 * later). [reason] is the platform's REASON_ constant.
 */
class ExitRecord(val timestampMs: Long, val reason: Int, val description: String?)

/** Which recorded exits to report: native crashes and ANRs newer than the last one seen, once each. */
object ExitReasons {
    /** ApplicationExitInfo.REASON_CRASH_NATIVE. */
    const val REASON_CRASH_NATIVE: Int = 5

    /** ApplicationExitInfo.REASON_ANR. */
    const val REASON_ANR: Int = 6

    /** What to report, oldest first, and the new last-seen timestamp. */
    class Selection(val toReport: List<AutoError>, val lastSeenMs: Long)

    /**
     * With no [lastSeenMs] (the first read on this install, or after an
     * opt-out) nothing is reported and the baseline becomes [nowMs], so an
     * exit from before capture was allowed is never sent.
     */
    @JvmStatic
    fun select(records: List<ExitRecord>, lastSeenMs: Long?, nowMs: Long): Selection {
        if (lastSeenMs == null) return Selection(emptyList(), maxOf(nowMs, records.maxOfOrNull { it.timestampMs } ?: 0L))
        val fresh = records
            .filter { it.timestampMs > lastSeenMs && (it.reason == REASON_CRASH_NATIVE || it.reason == REASON_ANR) }
            .sortedBy { it.timestampMs }
        val newest = maxOf(lastSeenMs, records.maxOfOrNull { it.timestampMs } ?: lastSeenMs)
        return Selection(fresh.map(::toError), newest)
    }

    private fun toError(record: ExitRecord): AutoError {
        val native = record.reason == REASON_CRASH_NATIVE
        val label = if (native) "Native crash" else "ANR"
        val description = record.description?.let { ErrorScrub.cut(it, ErrorCaps.MAX_MESSAGE_CHARS * 4) }?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
        return AutoError.of(
            source = EventSource.CRASH,
            severity = Severity.CRITICAL,
            message = if (description.isEmpty()) label else "$label: $description",
            type = if (native) "REASON_CRASH_NATIVE" else "REASON_ANR",
            atMs = record.timestampMs,
        )
    }
}

/**
 * The trail: an in-memory ring of the last 20 lines, `+<ms> <level> <text>`,
 * each scrubbed and cut to 120 characters. Thread safe, because a crash
 * handler reads it from the crashing thread.
 */
class TrailRing {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(sinceStartMs: Long, level: String, text: String) {
        val flat = ErrorScrub.cut(text, ErrorCaps.MAX_TRAIL_LINE_CHARS * 4).replace(Regex("""\s+"""), " ").trim()
        val line = "+${sinceStartMs.coerceAtLeast(0L)} ${level.trim().ifEmpty { "info" }} $flat"
        lines.addLast(ErrorScrub.clean(line, ErrorCaps.MAX_TRAIL_LINE_CHARS))
        while (lines.size > ErrorCaps.TRAIL_LINES) lines.removeFirst()
    }

    @Synchronized
    fun clear() = lines.clear()

    @get:Synchronized
    val size: Int get() = lines.size

    /** Oldest first, joined by newlines, oldest lines dropped to fit 1,500 characters. Null when empty. */
    @Synchronized
    fun text(): String? {
        if (lines.isEmpty()) return null
        val kept = ArrayList(lines)
        var text = kept.joinToString("\n")
        while (text.length > ErrorCaps.MAX_TRAIL_CHARS && kept.size > 1) {
            kept.removeAt(0)
            text = kept.joinToString("\n")
        }
        return ErrorScrub.cut(text, ErrorCaps.MAX_TRAIL_CHARS)
    }
}

/**
 * The capture gate and device caps, as a pure state machine. Not
 * synchronized: one thread (the SDK's worker) owns it.
 *
 * Before the game's switch is known ([answered] false) captures are held,
 * at most [ErrorCaps.BOOT_HOLD]. [onAnswer] releases them when capture is
 * on and discards them unsent when it is off. Every capture that leaves
 * through [offer] or [onAnswer] has passed the per-session caps.
 */
class ErrorCaptureState(private val nowMs: () -> Long) {
    var answered: Boolean = false
        private set

    /** The game's errorCapture.enabled. Absent in an answer means on. */
    var enabled: Boolean = true
        private set

    /** The game's errorCapture.trail. Absent means off. */
    var trail: Boolean = false
        private set

    private val held = ArrayList<AutoError>()
    private var total = 0
    private var trails = 0
    private val perKey = HashMap<String, Int>()
    private var tokens = ErrorCaps.PER_MINUTE.toDouble()
    private var refilledAt = nowMs()

    /** Captures dropped by a cap or the boot hold. */
    var capped: Long = 0
        private set

    val heldCount: Int get() = held.size

    /**
     * Reads `errorCapture: { enabled, trail }`. [enabled] and [trail] null
     * mean the key was absent: a known switch is kept, an unknown one reads
     * as enabled with no trail. Returns the held captures to queue now as
     * event data (empty when capture is off).
     */
    fun onAnswer(enabled: Boolean?, trail: Boolean?, present: Boolean): List<Pair<AutoError, Map<String, Any?>>> {
        if (present) {
            this.enabled = enabled != false
            this.trail = trail == true
        } else if (!answered) {
            this.enabled = true
            this.trail = false
        }
        answered = true
        val release = ArrayList(held)
        held.clear()
        if (!this.enabled) return emptyList()
        return release.mapNotNull { e -> admit(e)?.let { e to it } }
    }

    /**
     * One capture. Before the answer it is held (null returned); after, it is
     * returned as event data when inside the caps, else null. Discarded
     * without counting while capture is known to be off.
     */
    fun offer(error: AutoError): Map<String, Any?>? {
        if (answered && !enabled) return null
        if (!answered) {
            if (held.size < ErrorCaps.BOOT_HOLD) held.add(error) else capped++
            return null
        }
        return admit(error)
    }

    /** Drops anything held, for a capture switched off or an opt-out. */
    fun discardHeld() = held.clear()

    /** A new session: the per-session caps start again. */
    fun newSession() {
        total = 0
        trails = 0
        perKey.clear()
    }

    private fun admit(error: AutoError): Map<String, Any?>? {
        val now = nowMs()
        tokens = minOf(ErrorCaps.PER_MINUTE.toDouble(), tokens + (now - refilledAt).coerceAtLeast(0L) * ErrorCaps.PER_MINUTE / 60_000.0)
        refilledAt = now
        val key = error.dedupeKey
        val seen = perKey[key] ?: 0
        if (total >= ErrorCaps.PER_SESSION || tokens < 1.0 || seen >= ErrorCaps.PER_KEY) {
            capped++
            return null
        }
        total++
        tokens -= 1.0
        perKey[key] = seen + 1

        val data = LinkedHashMap<String, Any?>()
        data["rs_kind"] = "error"
        data["rs_severity"] = error.severity
        data["rs_message"] = error.message
        data["rs_source"] = error.source
        error.type?.let { data["rs_error_type"] = it }
        error.stack?.let { data["rs_stack"] = it }
        if (trail && error.trail != null && trails < ErrorCaps.TRAILS) {
            trails++
            data["rs_trail"] = error.trail
        }
        return data
    }
}
