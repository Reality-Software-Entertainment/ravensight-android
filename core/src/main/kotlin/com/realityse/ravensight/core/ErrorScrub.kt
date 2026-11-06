package com.realityse.ravensight.core

/**
 * The on-device scrub for automatically captured error text (message,
 * stack, trail): the same rules as the server, applied before anything is
 * queued. Pure and idempotent: scrub(scrub(x)) == scrub(x).
 *
 * In order:
 *  1. A URL (`scheme://...`) loses any `user:password@`, its query and its fragment.
 *  2. `Bearer <token>`, a JWT-shaped string and a `gt_` key become `[token]`.
 *  3. An email address becomes `[email]`.
 *  4. `/Users/<name>`, `/home/<name>` and `C:\Users\<name>` become `~`.
 *  5. IPv4 and IPv6 addresses (with a digit) become `[ip]`.
 *  6. A run of 32 or more characters from `[A-Za-z0-9+=_-]` that contains
 *     at least one digit becomes `[token]`. `/` is not in the class, so a
 *     file path survives, and a run with no digit is an identifier.
 *
 * Manual `trackError` text is not scrubbed here: the server scrubs it.
 */
object ErrorScrub {
    private val URL_QUERY = Regex("""\b([a-zA-Z][a-zA-Z0-9+.\-]*://)(?:[^\s/?#@'"<>]*@)?([^\s?#'"<>]*)(?:[?#][^\s'"<>]*)?""")
    private val BEARER = Regex("""\bBearer\s+[A-Za-z0-9\-._~+/]+=*""", RegexOption.IGNORE_CASE)
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")
    private val GT_KEY = Regex("""\bgt_[A-Za-z0-9_]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val HOME_UNIX = Regex("""/(?:Users|home)/[^/\s'"]+""")
    private val HOME_WINDOWS = Regex("""\b[A-Za-z]:[\\/][Uu][Ss][Ee][Rr][Ss][\\/][^\\/\s'"]+""")
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val IPV6_FULL = Regex("""\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b""")
    private val IPV6_SHORT = Regex("""(?:\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6})?::(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6}\b)?""")
    private val LONG_RUN = Regex("""[A-Za-z0-9+=_-]{32,}""")
    private val DIGIT = Regex("""\d""")

    @JvmStatic
    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = URL_QUERY.replace(text) { it.groupValues[1] + it.groupValues[2] }
        out = BEARER.replace(out, "[token]")
        out = JWT.replace(out, "[token]")
        out = GT_KEY.replace(out, "[token]")
        out = EMAIL.replace(out, "[email]")
        out = HOME_WINDOWS.replace(out, "~")
        out = HOME_UNIX.replace(out, "~")
        out = IPV4.replace(out, "[ip]")
        out = IPV6_FULL.replace(out, "[ip]")
        out = IPV6_SHORT.replace(out) { if (DIGIT.containsMatchIn(it.value)) "[ip]" else it.value }
        out = LONG_RUN.replace(out) { if (DIGIT.containsMatchIn(it.value)) "[token]" else it.value }
        return out
    }

    /** Scrub, then cut to [max] code points. */
    @JvmStatic
    fun clean(text: String, max: Int): String = cut(scrub(text), max)

    /** Cuts to at most [max] code points, never splitting a surrogate pair. */
    @JvmStatic
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = text.offsetByCodePoints(0, minOf(max, text.codePointCount(0, text.length)))
        return text.substring(0, end)
    }

    /**
     * A message with its variable parts replaced, for the local dedupe key:
     * URLs, quoted text, UUIDs, hex runs of 8 or more and numbers become
     * placeholders, whitespace collapsed.
     */
    @JvmStatic
    fun messageTemplate(message: String): String = message
        .replace(Regex("""\b[a-zA-Z][a-zA-Z0-9+.\-]*://\S+"""), "<url>")
        .replace(Regex(""""[^"\n]*"|'[^'\n]*'|`[^`\n]*`"""), "<str>")
        .replace(Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""), "<id>")
        .replace(Regex("""\b(?:0x)?[0-9a-fA-F]{8,}\b"""), "<hex>")
        .replace(Regex("""\b\d+(?:\.\d+)*\b"""), "<n>")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private val FRAME = Regex("""^\s*at\s+([\w$.<>-]+)\(([^:)]+)(?::\d+)?\)""")
    private val NOT_IN_APP = Regex("""^(?:java\.|javax\.|jdk\.|sun\.|kotlin\.|kotlinx\.|android\.|androidx\.|com\.android\.|dalvik\.|libcore\.|com\.realityse\.ravensight\.)""")

    /** The file of the first in-app JVM frame (`at a.b.C.m(File.kt:12)`), or "". */
    @JvmStatic
    fun firstFrame(stack: String?): String {
        if (stack.isNullOrEmpty()) return ""
        for (line in stack.lineSequence()) {
            val match = FRAME.find(line) ?: continue
            if (NOT_IN_APP.containsMatchIn(match.groupValues[1])) continue
            return match.groupValues[2]
        }
        return ""
    }
}
