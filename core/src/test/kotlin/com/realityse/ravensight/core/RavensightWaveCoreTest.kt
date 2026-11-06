package com.realityse.ravensight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The delivery contract's queue and response rules, in the pure core. */
class RavensightWaveCoreTest {
    private var now = 1_000_000_000_000L

    private fun booted(cap: Int = 500): RavensightCore {
        val c = RavensightCore(RavensightCoreConfig(maxQueueSize = cap, random = { 1.0 })) { now }
        c.onSettingsResult(200, true)
        return c
    }

    private fun open(c: RavensightCore, session: String = "ses_one") {
        c.requestSession()
        assertTrue(c.nextAction() is RavensightAction.OpenSession)
        c.onSessionResult(201, "tok_$session", expiresInSec = 3_600L, origin = mapOf("session" to session, "stamp" to "o1.a.b"))
    }

    @Test
    fun `events tracked before a session get its origin and restored ones keep theirs`() {
        val c = booted()
        c.track("early")
        c.restore(listOf(RavensightEvent("old", emptyMap(), 1L, origin = mapOf("session" to "ses_old"), restored = true)))
        open(c)
        val events = c.snapshot()
        assertEquals("ses_old", events.first { it.name == "old" }.originSession)
        assertEquals("ses_one", events.first { it.name == "early" }.originSession)
        c.track("later")
        assertEquals("ses_one", c.snapshot().last().originSession)
    }

    @Test
    fun `automatic errors queue behind gameplay and go first at the cap`() {
        val c = booted(cap = 3)
        c.track("err", auto = true)
        c.track("play_1")
        c.track("play_2")
        assertEquals(listOf("play_1", "play_2", "err"), c.snapshot().map { it.name })
        c.track("play_3")
        assertEquals(listOf("play_1", "play_2", "play_3"), c.snapshot().map { it.name })
        assertFalse(c.track("err_2", auto = true))
        assertEquals(2L, c.stats.dropped)
    }

    @Test
    fun `a gameplay event never lands inside a batch in flight`() {
        val c = booted()
        open(c)
        c.track("err", auto = true)
        val batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(1, batch.events.size)
        c.track("play")
        c.onBatchResult(202)
        assertEquals(listOf("play"), c.snapshot().map { it.name })
    }

    @Test
    fun `an indexed 400 drops one, another 400 drops the batch, a 413 splits`() {
        val c = booted()
        open(c)
        listOf("a", "bad", "c").forEach { c.track(it) }
        c.nextAction()
        assertEquals(1, (c.onBatchResult(400, errorCode = "invalid_event", index = 1) as BatchOutcome.EventRejected).count)
        assertEquals(listOf("a", "c"), c.snapshot().map { it.name })
        c.nextAction()
        assertEquals(2, (c.onBatchResult(400, errorCode = "invalid_json") as BatchOutcome.EventRejected).count)
        assertEquals(0, c.queueSize)
        repeat(4) { c.track("e$it") }
        c.nextAction()
        assertEquals(2, (c.onBatchResult(413) as BatchOutcome.BatchSplit).newLimit)
    }

    @Test
    fun `a 415 on a compressed body asks for plain bodies once`() {
        val c = booted()
        open(c)
        c.track("a")
        c.nextAction()
        assertTrue(c.onBatchResult(415, compressed = true) is BatchOutcome.PlainBodyRequired)
        assertTrue(c.gzipRefused)
        c.nextAction()
        assertTrue(c.onBatchResult(415, compressed = false) is BatchOutcome.EventRejected)
    }

    @Test
    fun `a flush cuts a backoff but never a Retry-After`() {
        val c = booted()
        open(c)
        c.track("a")
        c.nextAction()
        c.onBatchResult(429, retryAfterSec = 30L)
        c.flushNow()
        assertTrue(c.nextAction() is RavensightAction.WaitUntil)
        now += 30_000L
        c.nextAction()
        c.onBatchResult(500)
        assertTrue(c.nextAction() is RavensightAction.WaitUntil)
        c.flushNow()
        assertTrue(c.nextAction() is RavensightAction.SendBatch)
    }

    @Test
    fun `a batch 403 tracking_disabled keeps the events and signing is memory only`() {
        val c = booted()
        c.requestSession()
        c.nextAction()
        c.onSessionResult(201, "tok", expiresInSec = 3_600L, signingSecret = "s3cret", serverTimeSec = now / 1000 + 100)
        assertEquals("s3cret", c.signingSecret)
        assertEquals(100_000L, c.clockOffsetMs)
        c.track("kept")
        c.nextAction()
        c.onBatchResult(403, errorCode = "tracking_disabled")
        assertEquals(1, c.queueSize)
        assertNull(c.signingSecret)
    }

    // --- The queue rules agreed for every SDK (rules 1 and 2) -----------------

    @Test
    fun `rule 1, gameplay goes before captures and a game's own error is gameplay`() {
        val c = booted()
        c.track("capture_1", mapOf("rs_kind" to "error"), auto = true)
        c.track("own_error", mapOf("rs_kind" to "error", "rs_source" to "crash"))
        c.track("capture_2", mapOf("rs_kind" to "error"), auto = true)
        c.track("play")
        open(c)
        val batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(listOf("own_error", "play", "capture_1", "capture_2"), batch.events.map { it.name })
    }

    @Test
    fun `rule 2, an incoming capture evicts the oldest capture and never gameplay`() {
        val c = booted(cap = 3)
        c.track("play_1")
        c.track("capture_1", auto = true)
        c.track("play_2")
        assertTrue(c.track("capture_2", auto = true))
        assertEquals(listOf("play_1", "play_2", "capture_2"), c.snapshot().map { it.name })
        c.track("play_3") // a gameplay event evicts the capture
        assertEquals(listOf("play_1", "play_2", "play_3"), c.snapshot().map { it.name })
        assertFalse("no capture queued: the incoming one is dropped", c.track("capture_3", auto = true))
        assertEquals(listOf("play_1", "play_2", "play_3"), c.snapshot().map { it.name })
        assertEquals(3L, c.stats.dropped)
    }
}
