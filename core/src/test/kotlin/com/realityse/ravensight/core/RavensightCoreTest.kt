package com.realityse.ravensight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests driving the protocol state machine with a stepped fake
 * clock. No Android, no network.
 */
class RavensightCoreTest {

    private var now = 1_000_000_000_000L
    private fun core(config: RavensightCoreConfig = RavensightCoreConfig()) =
        RavensightCore(config) { now }

    /** Walks a fresh core through settings ok + session ok. */
    private fun booted(config: RavensightCoreConfig = RavensightCoreConfig()): RavensightCore {
        val c = core(config)
        assertTrue(c.nextAction() is RavensightAction.CheckSettings)
        c.onSettingsResult(200, true)
        c.track("boot")
        assertTrue(c.nextAction() is RavensightAction.OpenSession)
        assertEquals(SessionOutcome.Ready, c.onSessionResult(201, "tok_1", expiresInSec = 86_400L))
        val batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(1, batch.events.size)
        assertTrue(c.onBatchResult(202) is BatchOutcome.Flushed)
        assertEquals(0, c.queueSize)
        return c
    }

    // --- Boot and kill switch ---------------------------------------------

    @Test
    fun `first action is the settings check`() {
        assertTrue(core().nextAction() is RavensightAction.CheckSettings)
    }

    @Test
    fun `kill switch disables tracking and clears the queue`() {
        val c = core()
        c.track("early")
        assertEquals(1, c.queueSize)
        assertFalse(c.onSettingsResult(200, false))
        assertFalse(c.isActive)
        assertEquals(0, c.queueSize)
        assertTrue(c.nextAction() is RavensightAction.Idle)
        assertFalse(c.track("later"))
        assertEquals(1, c.stats.dropped)
    }

    @Test
    fun `settings failure assumes tracking enabled`() {
        val c = core()
        assertTrue(c.onSettingsResult(0, null))
        assertTrue(c.isActive)
        val c2 = core()
        assertTrue(c2.onSettingsResult(503, null))
        assertTrue(c2.isActive)
    }

    @Test
    fun `events queue before the settings answer and survive it`() {
        val c = core()
        assertTrue(c.track("early", mapOf("k" to 1)))
        c.onSettingsResult(200, true)
        assertEquals(1, c.queueSize)
        assertTrue(c.nextAction() is RavensightAction.OpenSession)
    }

    // --- Session ------------------------------------------------------------

    @Test
    fun `no session is opened while the queue is empty`() {
        val c = core()
        c.onSettingsResult(200, true)
        assertTrue(c.nextAction() is RavensightAction.Idle)
    }

    @Test
    fun `requestSession opens a session with an empty queue`() {
        val c = core()
        c.onSettingsResult(200, true)
        c.requestSession()
        assertTrue(c.nextAction() is RavensightAction.OpenSession)
        c.onSessionResult(201, "tok", expiresInSec = 3_600L)
        assertTrue(c.isSessionValid())
        assertTrue(c.nextAction() is RavensightAction.Idle)
    }

    @Test
    fun `session expiresAt from the body is honored`() {
        val c = core()
        c.onSettingsResult(200, true)
        c.requestSession()
        c.onSessionResult(201, "tok", expiresAtSec = now / 1000L + 100L)
        assertTrue(c.isSessionValid())
        now += 96_000L // 100s - 5s skew = 95s of validity
        assertFalse(c.isSessionValid())
    }

    @Test
    fun `session expiry defaults to 24 hours when the body has neither field`() {
        val c = core()
        c.onSettingsResult(200, true)
        c.requestSession()
        c.onSessionResult(201, "tok")
        assertEquals(now + 86_400_000L, c.sessionExpiresAtMs)
    }

    @Test
    fun `a 201 without a token is a failure and schedules a retry`() {
        val c = core()
        c.onSettingsResult(200, true)
        c.track("e")
        val outcome = c.onSessionResult(201, null)
        assertTrue(outcome is SessionOutcome.Failed)
        assertEquals("malformed_response", (outcome as SessionOutcome.Failed).reason)
        assertTrue(c.nextAction() is RavensightAction.WaitUntil)
    }

    @Test
    fun `session failure backs off then retries the session`() {
        val c = core()
        c.onSettingsResult(200, true)
        c.track("e")
        c.onSessionResult(500, null)
        val wait = c.nextAction() as RavensightAction.WaitUntil
        assertEquals(now + 10_000L, wait.atMs)
        now = wait.atMs
        assertTrue(c.nextAction() is RavensightAction.OpenSession)
        assertEquals(1, c.queueSize)
    }

    @Test
    fun `session 429 honors Retry-After`() {
        val c = core()
        c.onSettingsResult(200, true)
        c.track("e")
        val outcome = c.onSessionResult(429, null, retryAfterSec = 42L, errorCode = "rate_limited")
        assertTrue(outcome is SessionOutcome.RateLimited)
        assertEquals(now + 42_000L, (c.nextAction() as RavensightAction.WaitUntil).atMs)
    }

    // --- Batching -----------------------------------------------------------

    @Test
    fun `flushes in batches of 50 until the queue drains`() {
        val c = booted()
        repeat(120) { c.track("e$it") }

        val first = c.nextAction() as RavensightAction.SendBatch
        assertEquals(50, first.events.size)
        assertEquals("e0", first.events[0].name)
        c.onBatchResult(202)
        assertEquals(70, c.queueSize)

        val second = c.nextAction() as RavensightAction.SendBatch
        assertEquals(50, second.events.size)
        assertEquals("e50", second.events[0].name)
        c.onBatchResult(202)

        val third = c.nextAction() as RavensightAction.SendBatch
        assertEquals(20, third.events.size)
        c.onBatchResult(202)

        assertEquals(0, c.queueSize)
        assertTrue(c.nextAction() is RavensightAction.Idle)
        assertEquals(121, c.stats.sent) // 120 + the boot event
    }

    @Test
    fun `events carry their capture timestamp in unix seconds`() {
        val c = booted()
        c.track("stamped")
        val batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(now / 1000L, batch.events[0].timestampSec)
    }

    @Test
    fun `queue caps at 500 dropping the oldest first`() {
        val c = booted()
        repeat(520) { c.track("e$it") }
        assertEquals(500, c.queueSize)
        assertEquals(20, c.stats.dropped)
        val batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals("e20", batch.events[0].name) // e0..e19 were dropped
    }

    @Test
    fun `custom queue cap is respected`() {
        val c = core(RavensightCoreConfig(maxQueueSize = 3))
        c.onSettingsResult(200, true)
        repeat(5) { c.track("e$it") }
        assertEquals(3, c.queueSize)
    }

    @Test
    fun `failed batches stay queued`() {
        val c = booted()
        repeat(10) { c.track("e$it") }
        c.nextAction()
        val outcome = c.onBatchResult(500)
        assertTrue(outcome is BatchOutcome.Failed)
        assertEquals("http_500", (outcome as BatchOutcome.Failed).reason)
        assertEquals(10, c.queueSize)
    }

    @Test
    fun `network failure keeps the batch and backs off`() {
        val c = booted()
        c.track("e")
        c.nextAction()
        val outcome = c.onBatchResult(0)
        assertEquals("network_error", (outcome as BatchOutcome.Failed).reason)
        assertEquals(1, c.queueSize)
        assertTrue(c.nextAction() is RavensightAction.WaitUntil)
    }

    // --- 401 re-authentication ----------------------------------------------

    @Test
    fun `a 401 invalidates the session and requeues the batch`() {
        val c = booted()
        repeat(5) { c.track("e$it") }
        c.nextAction()
        assertEquals(BatchOutcome.SessionExpired, c.onBatchResult(401))
        assertNull(c.sessionToken)
        assertEquals(5, c.queueSize)

        assertTrue(c.nextAction() is RavensightAction.OpenSession)
        c.onSessionResult(201, "tok_2", expiresInSec = 3_600L)
        val batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(5, batch.events.size)
        c.onBatchResult(202)
        assertEquals(0, c.queueSize)
    }

    @Test
    fun `repeated 401s on fresh tokens fall back to backoff instead of looping`() {
        val c = booted()
        c.track("e")
        repeat(3) {
            c.nextAction() // OpenSession is skipped while the token is valid
            val action = c.nextAction()
            if (action is RavensightAction.OpenSession) {
                c.onSessionResult(201, "tok_$it", expiresInSec = 3_600L)
                c.nextAction()
            }
            c.onBatchResult(401)
        }
        // Third consecutive 401 scheduled a backoff.
        assertTrue(c.nextAction() is RavensightAction.WaitUntil)
        assertEquals(1, c.queueSize)
    }

    @Test
    fun `a 202 resets the consecutive 401 counter`() {
        val c = booted()
        c.track("e")
        repeat(2) {
            c.nextAction()
            var action = c.nextAction()
            if (action is RavensightAction.OpenSession) {
                c.onSessionResult(201, "t$it", expiresInSec = 3_600L)
                c.nextAction()
            }
            c.onBatchResult(401)
        }
        c.nextAction() // OpenSession
        c.onSessionResult(201, "good", expiresInSec = 3_600L)
        c.nextAction() // SendBatch
        c.onBatchResult(202)

        // Two more 401s should not trip the guard (counter was reset).
        c.track("f")
        repeat(2) {
            var action = c.nextAction()
            if (action is RavensightAction.OpenSession) {
                c.onSessionResult(201, "u$it", expiresInSec = 3_600L)
                action = c.nextAction()
            }
            assertTrue(action is RavensightAction.SendBatch)
            c.onBatchResult(401)
        }
        assertTrue(c.nextAction() is RavensightAction.OpenSession)
    }

    // --- Backoff and Retry-After ---------------------------------------------

    @Test
    fun `backoff grows 10s 20s 40s and caps at 300s`() {
        val c = booted()
        c.track("e")
        val expected = longArrayOf(10_000, 20_000, 40_000, 80_000, 160_000, 300_000, 300_000)
        for (wait in expected) {
            var action = c.nextAction()
            if (action is RavensightAction.WaitUntil) {
                now = action.atMs
                action = c.nextAction()
            }
            assertTrue(action is RavensightAction.SendBatch)
            c.onBatchResult(503)
            val next = c.nextAction() as RavensightAction.WaitUntil
            assertEquals(wait, next.atMs - now)
        }
    }

    @Test
    fun `a successful flush resets the backoff`() {
        val c = booted()
        c.track("e")
        repeat(3) {
            var action = c.nextAction()
            if (action is RavensightAction.WaitUntil) {
                now = action.atMs
                action = c.nextAction()
            }
            c.onBatchResult(503)
        }
        now = (c.nextAction() as RavensightAction.WaitUntil).atMs
        c.nextAction()
        c.onBatchResult(202)

        c.track("f")
        c.nextAction()
        c.onBatchResult(503)
        val wait = c.nextAction() as RavensightAction.WaitUntil
        assertEquals(10_000L, wait.atMs - now) // back to the initial delay
    }

    @Test
    fun `batch 429 honors Retry-After and holds the queue`() {
        val c = booted()
        repeat(3) { c.track("e$it") }
        c.nextAction()
        val outcome = c.onBatchResult(429, retryAfterSec = 60L)
        assertTrue(outcome is BatchOutcome.RateLimited)
        assertEquals(3, c.queueSize)
        val wait = c.nextAction() as RavensightAction.WaitUntil
        assertEquals(now + 60_000L, wait.atMs)
        now = wait.atMs
        assertTrue(c.nextAction() is RavensightAction.SendBatch)
    }

    @Test
    fun `batch 429 without Retry-After uses exponential backoff`() {
        val c = booted()
        c.track("e")
        c.nextAction()
        c.onBatchResult(429)
        assertEquals(now + 10_000L, (c.nextAction() as RavensightAction.WaitUntil).atMs)
    }

    @Test
    fun `retryInMs reports the remaining wait`() {
        val c = booted()
        c.track("e")
        c.nextAction()
        c.onBatchResult(503)
        assertEquals(10_000L, c.retryInMs)
        now += 4_000L
        assertEquals(6_000L, c.retryInMs)
    }

    // --- 400 oversize split ---------------------------------------------------

    @Test
    fun `a 400 halves the batch down to one then drops the poisoned event`() {
        val c = booted()
        repeat(50) { c.track("e$it") }

        var batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(50, batch.events.size)
        var outcome = c.onBatchResult(400)
        assertEquals(25, (outcome as BatchOutcome.BatchSplit).newLimit)

        batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(25, batch.events.size)
        outcome = c.onBatchResult(400)
        assertEquals(12, (outcome as BatchOutcome.BatchSplit).newLimit)

        batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(12, batch.events.size)
        c.onBatchResult(400) // 6
        c.nextAction(); c.onBatchResult(400) // 3
        c.nextAction(); c.onBatchResult(400) // 1
        batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(1, batch.events.size)
        assertEquals("e0", batch.events[0].name)

        assertEquals(BatchOutcome.EventRejected, c.onBatchResult(400))
        assertEquals(49, c.queueSize) // e0 dropped, everything behind it freed

        // Batch limit is restored to 50 after the drop.
        batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(49, batch.events.size)
        assertEquals("e1", batch.events[0].name)
        c.onBatchResult(202)
        assertEquals(0, c.queueSize)
    }

    @Test
    fun `a 202 restores the full batch limit after a split`() {
        val c = booted()
        repeat(60) { c.track("e$it") }
        c.nextAction()
        c.onBatchResult(400) // limit 25
        c.nextAction()
        c.onBatchResult(202) // 25 accepted, limit back to 50
        val batch = c.nextAction() as RavensightAction.SendBatch
        assertEquals(35, batch.events.size)
    }

    @Test
    fun `a 400 does not schedule backoff`() {
        val c = booted()
        repeat(2) { c.track("e$it") }
        c.nextAction()
        c.onBatchResult(400)
        assertTrue(c.nextAction() is RavensightAction.SendBatch)
    }

    // --- Local opt-out ----------------------------------------------------------

    @Test
    fun `disabling locally discards the queue and stops all actions`() {
        val c = booted()
        repeat(4) { c.track("e$it") }
        c.setEnabled(false)
        assertEquals(0, c.queueSize)
        assertFalse(c.isActive)
        assertFalse(c.track("nope"))
        assertTrue(c.nextAction() is RavensightAction.Idle)

        c.setEnabled(true)
        assertTrue(c.track("yes"))
        assertTrue(c.nextAction() is RavensightAction.SendBatch)
    }

    // --- Session validity ----------------------------------------------------

    @Test
    fun `an expired session reopens before the next batch`() {
        val c = booted()
        c.track("e")
        now = c.sessionExpiresAtMs + 1_000L
        assertFalse(c.isSessionValid())
        assertTrue(c.nextAction() is RavensightAction.OpenSession)
    }

    @Test
    fun `the expiry skew treats an almost expired session as expired`() {
        val c = core()
        c.onSettingsResult(200, true)
        c.requestSession()
        c.onSessionResult(201, "tok", expiresInSec = 10L)
        assertTrue(c.isSessionValid())
        now += 6_000L // 10s - 5s skew = 5s of usable validity
        assertFalse(c.isSessionValid())
    }

    @Test
    fun `track rejects an empty name`() {
        val c = core()
        var threw = false
        try {
            c.track("")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `stats add up`() {
        val c = booted()
        repeat(3) { c.track("e$it") }
        c.nextAction()
        c.onBatchResult(202)
        val stats = c.stats
        assertEquals(0, stats.queued)
        assertEquals(4L, stats.sent)
        assertEquals(2L, stats.flushes)
        assertEquals(1L, stats.sessions)
        assertEquals(0L, stats.dropped)
    }
}
