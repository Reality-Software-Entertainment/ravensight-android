package com.realityse.ravensight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device scrub, caps, boot hold, trail and exit selection. Plain JVM. */
class ErrorCaptureTest {

    private var now = 1_800_000_000_000L

    // --- Scrub (same cases as the server's errorScrub tests) -----------------

    @Test
    fun `scrub matches the server rules`() {
        val cases = listOf(
            "mail bob.smith+x@example.co.uk now" to "mail [email] now",
            "GET https://api.example.com/v1/a?token=abc&x=1#frag failed" to "GET https://api.example.com/v1/a failed",
            "at https://user:secret@host.example/path" to "at https://host.example/path",
            "Authorization: Bearer abc.def-123_456" to "Authorization: [token]",
            "jwt eyJhbGciOi.eyJzdWIiOiIx.sig-_x end" to "jwt [token] end",
            "key gt_live_ab12CD34 and gt_mcp_x" to "key [token] and [token]",
            "at /Users/jacob/game/main.js:1" to "at ~/game/main.js:1",
            "at /home/al/game/main.lua" to "at ~/game/main.lua",
            "at C:\\Users\\Jo Ann\\x.cs" to "at ~ Ann\\x.cs",
            "at C:/Users/jo/x.cs" to "at ~/x.cs",
            "from 192.168.1.10 ok" to "from [ip] ok",
            "from 2001:db8::1 and ::1 ok" to "from [ip] and [ip] ok",
            "full fe80:0:0:0:0:0:0:1" to "full [ip]",
            "hash 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" to "hash [token]",
            "digits only 12345678901234567890123456789012" to "digits only [token]",
            "b64 QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0NTY3ODk=" to "b64 [token]",
        )
        for ((input, output) in cases) assertEquals(input, output, ErrorScrub.scrub(input))
    }

    /** Every case in the shared fixture (docs/errors/scrub-vectors.json), exactly. */
    @Test
    fun `scrub matches every shared vector`() {
        val json = javaClass.classLoader!!.getResource("scrub-vectors.json")!!.readText()
        val cases = Regex(""""in"\s*:\s*("(?:[^"\\]|\\.)*")\s*,\s*"out"\s*:\s*("(?:[^"\\]|\\.)*")""")
            .findAll(json).map { unquote(it.groupValues[1]) to unquote(it.groupValues[2]) }.toList()
        assertTrue(cases.size >= 20)
        for ((input, output) in cases) assertEquals(input, output, ErrorScrub.scrub(input))
    }

    /** A JSON string literal's value: enough of JSON for the fixture. */
    private fun unquote(literal: String): String {
        val body = literal.substring(1, literal.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch != '\\') { out.append(ch); i++; continue }
            when (val esc = body[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'u' -> { out.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                else -> out.append(esc)
            }
            i += 2
        }
        return out.toString()
    }

    @Test
    fun `a 200,000 character input is cut before scrubbing and stays fast`() {
        val huge = "a1:".repeat(40_000) + "x@y.io " + "Z".repeat(80_000)
        val started = System.nanoTime()
        val e = AutoError.of("crash", "critical", huge, stack = huge, type = huge, trail = huge, atMs = now)
        val ring = TrailRing()
        ring.add(0, "info", huge)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
        assertTrue(e.message.length <= ErrorCaps.MAX_MESSAGE_CHARS)
        assertTrue(e.stack!!.length <= ErrorCaps.MAX_STACK_CHARS)
        assertTrue(ring.text()!!.length <= ErrorCaps.MAX_TRAIL_LINE_CHARS)
        assertTrue(ErrorScrub.clean(huge, 200).length <= 200)
    }

    @Test
    fun `ipv6 needs a digit`() {
        assertEquals("at Face::Add (face.cpp:12)", ErrorScrub.scrub("at Face::Add (face.cpp:12)"))
        assertEquals("dead:beef:cafe:face:dead:beef:cafe:face", ErrorScrub.scrub("dead:beef:cafe:face:dead:beef:cafe:face"))
        assertEquals("[ip]", ErrorScrub.scrub("fe80::1"))
    }

    @Test
    fun `scrub turns secrets with slashes into tokens`() {
        assertEquals("aws [token] leaked", ErrorScrub.scrub("aws wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY1 leaked"))
        assertEquals("~/Projects/game2/src/levels/level_10_boss.gd", ErrorScrub.scrub("/Users/jacob/Projects/game2/src/levels/level_10_boss.gd"))
    }

    @Test
    fun `scrub leaves identifiers, frames and long paths with digits alone`() {
        val keep = listOf(
            "OnPointerClickHandlerImplementationForTheMenu failed",
            "at on_pointer_click_handler_for_the_menu (menu.gd:4)",
            "assets/sprites/player/idle.png",
            "at com.example.game.Kickoff.onKick(Kickoff.kt:12)",
            "Kick Off button: TypeError: x is undefined",
            // '/' is not in the token class, so a long path with digits survives.
            "assets/sprites/player_01/idle_animation/frame_02.png",
            "at com.example.game.level2.boss3.PhaseTwoController.update(PhaseTwoController.kt:88)",
        )
        for (text in keep) assertEquals(text, ErrorScrub.scrub(text))
    }

    @Test
    fun `scrub is idempotent and cut keeps surrogate pairs whole`() {
        val once = ErrorScrub.scrub("bob@x.io at https://h.io/a?b=1 from 10.0.0.1 gt_pub_abc Bearer t")
        assertEquals(once, ErrorScrub.scrub(once))
        assertEquals("", ErrorScrub.scrub(""))
        assertEquals("ab\uD83D\uDE00", ErrorScrub.cut("ab\uD83D\uDE00cd", 3))
    }

    @Test
    fun `template and first frame make the dedupe key`() {
        assertEquals(ErrorScrub.messageTemplate("Player 42 not found"), ErrorScrub.messageTemplate("Player 7 not found"))
        assertEquals("<str> at <url>", ErrorScrub.messageTemplate("'x' at https://a.b/c"))
        val stack = "\tat java.util.ArrayList.get(ArrayList.java:1)\n\tat com.realityse.ravensight.Ravensight.track(Ravensight.kt:3)\n\tat com.example.Game.tick(Game.kt:9)"
        assertEquals("Game.kt", ErrorScrub.firstFrame(stack))
        assertEquals("", ErrorScrub.firstFrame(null))
    }

    // --- Crash records ---------------------------------------------------------

    @Test
    fun `a crash is critical, typed and scrubbed`() {
        val t = IllegalStateException("user bob@example.com lost token gt_live_abc123")
        val e = AutoError.crash(t, now)
        assertEquals("crash", e.source)
        assertEquals("critical", e.severity)
        assertEquals("IllegalStateException", e.type)
        assertEquals("user [email] lost token [token]", e.message)
        assertTrue(e.stack!!.trimStart().startsWith("at "))
        assertTrue(e.stack!!.length <= ErrorCaps.MAX_STACK_CHARS)
        assertEquals("NullPointerException", AutoError.crash(NullPointerException(), now).message)
    }

    // --- Gate, boot hold and caps ------------------------------------------------

    private fun err(msg: String = "boom", type: String = "X", frame: String = "Game.kt") =
        AutoError.of("crash", "critical", msg, "\tat com.example.G.f($frame:1)", type, atMs = now)

    @Test
    fun `boot captures are held, at most five, then released when the answer says on`() {
        val s = ErrorCaptureState { now }
        repeat(7) { assertNull(s.offer(err("e$it", type = "T$it"))) }
        assertEquals(5, s.heldCount)
        assertEquals(2, s.capped)
        val released = s.onAnswer(true, false, present = true)
        assertEquals(5, released.size)
        assertEquals("crash", released[0].second["rs_source"])
        assertEquals(0, s.heldCount)
    }

    @Test
    fun `held captures are discarded unsent when the answer says off`() {
        val s = ErrorCaptureState { now }
        s.offer(err())
        assertTrue(s.onAnswer(false, false, present = true).isEmpty())
        assertNull(s.offer(err()))
        assertEquals(0, s.heldCount)
    }

    @Test
    fun `an answer without errorCapture means on with no trail, and keeps a known switch`() {
        val s = ErrorCaptureState { now }
        s.onAnswer(null, null, present = false)
        assertTrue(s.enabled)
        assertFalse(s.trail)
        s.onAnswer(false, true, present = true)
        s.onAnswer(null, null, present = false)
        assertFalse(s.enabled)
    }

    @Test
    fun `caps per key, token bucket and per session`() {
        val s = ErrorCaptureState { now }
        s.onAnswer(true, false, present = true)
        // 3 per dedupe key: numbers in the message do not make it new.
        assertEquals(3, (1..5).count { s.offer(err("Player $it missing")) != null })
        // The bucket had 6; 3 are left.
        assertEquals(3, (1..5).count { s.offer(err("other", type = "Y$it")) != null })
        now += 10_000 // one token back
        assertEquals(1, (1..3).count { s.offer(err("again", type = "Z$it")) != null })
        // Thirty a session, whatever the bucket says.
        var sent = 7
        repeat(100) {
            now += 60_000
            if (s.offer(err("new", type = "W$it")) != null) sent++
        }
        assertEquals(ErrorCaps.PER_SESSION, sent)
        s.newSession()
        assertTrue(s.offer(err("fresh", type = "F")) != null)
    }

    @Test
    fun `trail only when the switch is on and at most ten a session`() {
        val s = ErrorCaptureState { now }
        s.onAnswer(true, false, present = true)
        val withTrail = AutoError.of("crash", "critical", "m", type = "A", trail = "+1 info x", atMs = now)
        assertFalse(s.offer(withTrail)!!.containsKey("rs_trail"))
        s.onAnswer(true, true, present = true)
        var trails = 0
        repeat(20) {
            now += 60_000
            val data = s.offer(AutoError.of("crash", "critical", "m", type = "B$it", trail = "+1 info x", atMs = now))
            if (data?.containsKey("rs_trail") == true) trails++
        }
        assertEquals(ErrorCaps.TRAILS, trails)
    }

    @Test
    fun `event shape has only the contract keys at the top level`() {
        val s = ErrorCaptureState { now }
        s.onAnswer(true, false, present = true)
        val data = s.offer(err())!!
        assertEquals(setOf("rs_kind", "rs_severity", "rs_message", "rs_source", "rs_error_type", "rs_stack"), data.keys)
        assertEquals("error", data["rs_kind"])
        assertFalse(data.containsKey("rs_fp"))
    }

    // --- Trail ring ----------------------------------------------------------------

    @Test
    fun `trail ring keeps twenty scrubbed lines within the limits`() {
        val ring = TrailRing()
        assertNull(ring.text())
        repeat(25) { ring.add(it * 10L, "info", "line $it from bob@example.com " + "x".repeat(200)) }
        assertEquals(20, ring.size)
        val text = ring.text()!!
        val lines = text.split("\n")
        assertTrue(lines.first().startsWith("+50 info line 5 from [email]") || lines.size < 20)
        assertTrue(lines.all { it.length <= ErrorCaps.MAX_TRAIL_LINE_CHARS })
        assertTrue(text.length <= ErrorCaps.MAX_TRAIL_CHARS)
        assertTrue(lines.last().startsWith("+240 info line 24"))
    }

    // --- Exit reasons ------------------------------------------------------------------

    @Test
    fun `exit reasons report native crashes and ANRs once, after a baseline`() {
        val records = listOf(
            ExitRecord(now - 3_000, ExitReasons.REASON_ANR, "Input dispatching timed out for bob@example.com"),
            ExitRecord(now - 2_000, 4 /* REASON_CRASH: the JVM handler reports these */, "java crash"),
            ExitRecord(now - 1_000, ExitReasons.REASON_CRASH_NATIVE, null),
            ExitRecord(now - 9_000, ExitReasons.REASON_ANR, "old"),
        )
        val first = ExitReasons.select(records, null, now)
        assertTrue(first.toReport.isEmpty())
        assertEquals(now, first.lastSeenMs)

        val second = ExitReasons.select(records, now - 5_000, now)
        assertEquals(listOf("REASON_ANR", "REASON_CRASH_NATIVE"), second.toReport.map { it.type })
        assertEquals("ANR: Input dispatching timed out for [email]", second.toReport[0].message)
        assertEquals("Native crash", second.toReport[1].message)
        assertTrue(second.toReport.all { it.source == "crash" && it.severity == "critical" && it.stack == null })
        assertEquals(now - 1_000, second.lastSeenMs)
        assertTrue(ExitReasons.select(records, second.lastSeenMs, now).toReport.isEmpty())
    }
}
