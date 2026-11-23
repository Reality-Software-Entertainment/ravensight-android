package com.realityse.ravensight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identify's pure parts against the binding vectors
 * (docs/identify/identify-vectors.json, version 2), read from the fixture,
 * never hand typed. Plain JVM.
 */
class IdentifyTest {

    @Suppress("UNCHECKED_CAST")
    private val vectors: Map<String, Any?> =
        MiniJson.parse(javaClass.classLoader!!.getResource("identify-vectors.json")!!.readText()) as Map<String, Any?>

    private val salt get() = vectors["salt"] as String

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = vectors[key] as List<Map<String, Any?>>

    @Test
    fun `the fixture is version 2`() {
        assertEquals(2L, vectors["version"])
        assertEquals(RavensightIdentity.MAX_BYTES.toLong(), vectors["max_bytes"])
    }

    @Test
    fun `every accept vector hashes to its wire`() {
        var checked = 0
        for (case in list("accept")) {
            // A whole number cannot reach identify(accountId: String?) in Kotlin or Java.
            if (case["integer"] == true) continue
            val id = case["accountId"] as String
            val note = case["note"] as String
            assertNull(note, RavensightIdentity.refusal(id))
            val wire = RavensightIdentity.accountHash(id, salt)
            assertEquals(note, case["wire"], wire)
            assertEquals(note, case["playerId"], "px_" + wire.take(32))
            checked++
        }
        assertEquals(list("accept").size - 1, checked)
    }

    @Test
    fun `two salts give two wires for one account`() {
        @Suppress("UNCHECKED_CAST")
        val differ = vectors["salts_differ"] as Map<String, String>
        val id = differ.getValue("accountId")
        assertEquals(differ["wire_a"], RavensightIdentity.accountHash(id, differ.getValue("salt_a")))
        assertEquals(differ["wire_b"], RavensightIdentity.accountHash(id, differ.getValue("salt_b")))
        assertNotEquals(differ["wire_a"], differ["wire_b"])
    }

    @Test
    fun `every refuse vector is refused with its reason`() {
        var checked = 0
        for (case in list("refuse")) {
            val id = case["accountId"]
            // A fraction cannot be passed as a String; null can (from Java).
            if (id != null && id !is String) continue
            assertEquals(case["note"] as String, case["reason"], RavensightIdentity.refusal(id as String?))
            checked++
        }
        assertEquals(list("refuse").size - 1, checked)
    }

    @Test
    fun `answers map to the contract's actions`() {
        assertSame(RavensightIdentity.Outcome.DONE, RavensightIdentity.outcome(200, null))
        assertSame(RavensightIdentity.Outcome.STOP_FOR_LAUNCH, RavensightIdentity.outcome(404, null))
        assertSame(RavensightIdentity.Outcome.STOP_FOR_LAUNCH, RavensightIdentity.outcome(400, "invalid_account_hash"))
        for ((status, error) in listOf(400 to "no_session", 409 to "session_taken", 409 to "synthetic_session",
            429 to "identify_limited", 429 to "rate_limited", 429 to "quota_exceeded", 503 to null, 500 to null, 0 to null)) {
            assertSame("$status $error", RavensightIdentity.Outcome.NEXT_SESSION, RavensightIdentity.outcome(status, error))
        }
        assertEquals(3, RavensightIdentity.Outcome.values().size)
    }

    // --- The state ----------------------------------------------------------

    private val saved = mutableListOf<StoredIdentity?>()
    private fun state(stored: StoredIdentity? = null) = IdentifyState(stored) { saved += it }

    @Test
    fun `an id waits in memory for the salt, then is stored and sent once per session`() {
        val s = state()
        assertTrue(s.accept("u1", canStore = true))
        assertTrue(s.isHeld)
        assertSame(IdentifyStep.NeedSession, s.next(null, canStore = true))
        s.onSessionAnswer(salt)
        val send = s.next("S1", canStore = true) as IdentifyStep.Send
        assertEquals(RavensightIdentity.accountHash("u1", salt), send.accountHash)
        assertEquals("S1", send.token)
        assertFalse(s.isHeld)
        assertEquals(listOf(StoredIdentity(salt, send.accountHash)), saved)
        assertSame(IdentifyStep.None, s.next("S1", canStore = true))
        assertTrue(s.isSame("u1"))
        assertFalse(s.isSame("u2"))
        assertTrue(s.next("S2", canStore = true) is IdentifyStep.Send)
    }

    @Test
    fun `a missing salt drops the held id for the launch`() {
        val s = state()
        s.accept("u1", canStore = true)
        s.onSessionAnswer(null)
        assertFalse(s.isHeld)
        assertFalse(s.accept("u2", canStore = true))
        assertSame(IdentifyStep.None, s.next("S1", canStore = true))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a different salt deletes the stored identity`() {
        val s = state(StoredIdentity("other", "abc"))
        s.onSessionAnswer(salt)
        assertNull(s.stored)
        assertEquals(listOf<StoredIdentity?>(null), saved)
        assertSame(IdentifyStep.None, s.next("S1", canStore = true))
    }

    @Test
    fun `an answer from before forget is stale`() {
        val s = state()
        s.onSessionAnswer(salt)
        s.accept("u1", canStore = true)
        val send = s.next("S1", canStore = true) as IdentifyStep.Send
        assertTrue(s.forget())
        assertNull(s.onAnswer(send.generation, 200, null))
        assertFalse(s.forget())
    }

    @Test
    fun `404 stops sending for the launch`() {
        val s = state()
        s.onSessionAnswer(salt)
        s.accept("u1", canStore = true)
        val send = s.next("S1", canStore = true) as IdentifyStep.Send
        assertSame(RavensightIdentity.Outcome.STOP_FOR_LAUNCH, s.onAnswer(send.generation, 404, null))
        assertSame(IdentifyStep.None, s.next("S2", canStore = true))
        assertTrue(s.stored != null)
    }

    @Test
    fun `the same id before the salt is held, not skipped`() {
        val s = state(StoredIdentity(salt, RavensightIdentity.accountHash("u1", salt)))
        assertSame(IdentifyRelation.HOLD, s.relation("u1"))
        assertSame(IdentifyRelation.OTHER, s.relation("u2"))
        s.onSessionAnswer(salt)
        assertSame(IdentifyRelation.SAME, s.relation("u1"))
        assertSame(IdentifyRelation.NEW, state().relation("u1"))
    }

    @Test
    fun `a second no_session in a row stops, anything else resets the count`() {
        val s = state()
        s.onSessionAnswer(salt)
        s.accept("u1", canStore = true)
        fun answer(token: String, status: Int, error: String?) =
            s.onAnswer((s.next(token, canStore = true) as IdentifyStep.Send).generation, status, error)
        assertSame(RavensightIdentity.Outcome.NEXT_SESSION, answer("S1", 400, "no_session"))
        assertSame(RavensightIdentity.Outcome.NEXT_SESSION, answer("S2", 503, null))
        assertSame(RavensightIdentity.Outcome.NEXT_SESSION, answer("S3", 400, "no_session"))
        assertSame(RavensightIdentity.Outcome.STOP_FOR_LAUNCH, answer("S4", 400, "no_session"))
        assertSame(IdentifyStep.None, s.next("S5", canStore = true))
    }
}

/** Just enough JSON for the fixtures: objects, arrays, strings, numbers, true, false, null. */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.ws()
        check(p.i == text.length) { "trailing text at ${p.i}" }
        return value
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val key = str()
                ws(); check(s[i] == ':'); i++
                out[key] = value()
                ws()
                if (s[i] == ',') { i++; continue }
                check(s[i] == '}'); i++
                return out
            }
        }
        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                ws()
                if (s[i] == ',') { i++; continue }
                check(s[i] == ']'); i++
                return out
            }
        }
        fun str(): String {
            check(s[i] == '"'); i++
            val out = StringBuilder()
            while (s[i] != '"') {
                val ch = s[i]
                if (ch != '\\') { out.append(ch); i++; continue }
                when (val esc = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> out.append(esc)
                }
                i += 2
            }
            i++
            return out.toString()
        }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDouble()
        }
    }
}
