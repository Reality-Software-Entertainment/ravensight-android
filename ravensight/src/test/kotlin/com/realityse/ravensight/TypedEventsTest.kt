package com.realityse.ravensight

import android.app.Application
import android.content.Context
import android.util.Log
import android.view.Choreographer
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Feeds `fps` frames across exactly one second after [from], returning the window end. */
private fun feedWindow(sampler: FrameSampler, from: Long, fps: Int): Long {
    for (i in 1 until fps) sampler.onFrame(from + i * (1_000_000_000L / fps))
    val end = from + 1_000_000_000L
    sampler.onFrame(end)
    return end
}

/** Stands in for the Choreographer: records the one pending callback so tests can fire frames. */
private class FakeScheduler : FrameScheduler {
    var pending: Choreographer.FrameCallback? = null
    var posts = 0
    var removes = 0
    private var t = 0L

    override fun post(callback: Choreographer.FrameCallback) {
        pending = callback
        posts++
    }

    override fun remove(callback: Choreographer.FrameCallback) {
        if (pending === callback) pending = null
        removes++
    }

    /** Fires `n` frames one second apart, so each yields one sample, if a callback is pending. */
    fun frames(n: Int) {
        repeat(n) {
            val cb = pending ?: return
            pending = null
            cb.doFrame(t)
            t += 1_000_000_000L
        }
    }
}

/** The typed event helpers and opt-in health sampling, on the same fake transport and manual worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypedEventsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private val worker = ManualWorker { now }
    private val http = FakeTransport()
    private var frameClock = 0L

    @Before
    fun setUp() {
        Ravensight.resetForTest()
        app.getSharedPreferences(Ravensight.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        Ravensight.resetForTest()
    }

    private fun start(performance: Boolean = false, verbose: Boolean = false, scheduler: FrameScheduler? = null) {
        Ravensight.initialize(
            app,
            RavensightConfig(
                ingestKey = "gt_live_test",
                apiUrl = "https://api.example.test/",
                gameVersion = "1.0.0",
                verboseLogging = verbose,
                performance = performance,
            ),
            null,
            RavensightDeps(
                transport = http,
                clockMs = { now },
                worker = worker,
                mainPost = { it.run() },
                observeProcessLifecycle = false,
                registerActivityCallbacks = false,
                frameCallbacks = false,
                frameScheduler = scheduler,
                memoryMb = { 128.5 },
                bootMs = { 900L },
            ),
        )
        worker.runDue()
        foreground()
    }

    private fun background() {
        Ravensight.controllerForTest!!.onBackgroundSignal()
        worker.runDue()
    }

    private fun foreground() {
        Ravensight.controllerForTest!!.onForegroundSignal()
        worker.runDue()
    }

    private fun flushed(): List<JSONObject> {
        Ravensight.flush()
        worker.runDue()
        return http.requests.filter { it.path == "/track/batch" }.flatMap { r ->
            val arr = JSONObject(r.body!!).getJSONArray("events")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    private fun last(name: String): JSONObject = flushed().last { it.getString("event") == name }

    private fun keys(o: JSONObject): Set<String> {
        assertTrue("typed events carry a dedupe id", o.getString("rs_event_id").matches(Regex("^[A-Za-z0-9_-]{1,64}$")))
        return o.keys().asSequence().toSet() - "rs_event_id"
    }

    /** One window per entry in [fpsList], on the live controller's sampler. */
    private fun feed(fpsList: List<Int>) {
        val sampler = Ravensight.controllerForTest!!.frameSampler
        sampler.resetWindow()
        sampler.onFrame(frameClock) // opens the window
        for (fps in fpsList) frameClock = feedWindow(sampler, frameClock, fps)
    }

    // --- Helpers ---------------------------------------------------------

    @Test
    fun `purchase sets the exact property set, upper-cases the currency and defaults the name`() {
        start()
        assertTrue(Ravensight.trackPurchase(499, "usd", "bundle", "starter", cart = "c1", transactionId = "t1"))
        val d = last("purchase").getJSONObject("data")
        assertEquals(
            setOf("rs_kind", "rs_amount", "rs_currency", "rs_item_type", "rs_item_id", "rs_cart", "rs_transaction_id"),
            keys(d),
        )
        assertEquals("purchase", d.getString("rs_kind"))
        assertEquals(499, d.getInt("rs_amount"))
        assertEquals("USD", d.getString("rs_currency"))
        assertEquals("bundle", d.getString("rs_item_type"))
        assertEquals("starter", d.getString("rs_item_id"))
        assertEquals("c1", d.getString("rs_cart"))
        assertEquals("t1", d.getString("rs_transaction_id"))
    }

    @Test
    fun `purchase omits optional keys, accepts a digit string and passes a wrong amount through`() {
        start()
        Ravensight.trackPurchase("1299", "eur", "skin", "red")
        Ravensight.trackPurchase(4.99, "usd", "skin", "red", eventName = "iap")
        val events = flushed()
        val a = events.first { it.getString("event") == "purchase" }.getJSONObject("data")
        assertEquals("1299", a.getString("rs_amount"))
        assertFalse(a.has("rs_cart"))
        assertFalse(a.has("rs_transaction_id"))
        val b = events.first { it.getString("event") == "iap" }.getJSONObject("data")
        assertEquals(4.99, b.getDouble("rs_amount"), 0.0)
    }

    @Test
    fun `reserved keys win over caller keys and the extra map is kept`() {
        start()
        Ravensight.trackPurchase(
            100, "usd", "a", "b",
            extra = mapOf("rs_kind" to "death", "rs_currency" to "xxx", "level" to "cave"),
        )
        val d = last("purchase").getJSONObject("data")
        assertEquals("purchase", d.getString("rs_kind"))
        assertEquals("USD", d.getString("rs_currency"))
        assertEquals("cave", d.getString("level"))
    }

    @Test
    fun `resource defaults the name by flow, sets exactly its keys and honours an override`() {
        start()
        Ravensight.trackResource("source", "gold", 25, itemType = "quest", itemId = "q1")
        Ravensight.trackResource("sink", "gems", 2.5)
        Ravensight.trackResource("sink", "gems", 1, eventName = "spent")
        val events = flushed()
        val src = events.first { it.getString("event") == "resource_source" }.getJSONObject("data")
        assertEquals(setOf("rs_kind", "rs_flow", "rs_resource", "rs_amount", "rs_item_type", "rs_item_id"), keys(src))
        assertEquals("resource", src.getString("rs_kind"))
        assertEquals("source", src.getString("rs_flow"))
        assertEquals("gold", src.getString("rs_resource"))
        assertEquals(25, src.getInt("rs_amount"))
        val sink = events.first { it.getString("event") == "resource_sink" }.getJSONObject("data")
        assertEquals(setOf("rs_kind", "rs_flow", "rs_resource", "rs_amount"), keys(sink))
        assertEquals(2.5, sink.getDouble("rs_amount"), 0.0)
        assertTrue(events.any { it.getString("event") == "spent" })
    }

    @Test
    fun `progression defaults the name by status and carries only what was given`() {
        start()
        Ravensight.trackProgression("start", "world1")
        Ravensight.trackProgression("complete", "world1", "cave", "boss", score = 1200)
        Ravensight.trackProgression("fail", "w", eventName = "oops")
        val events = flushed()
        val s = events.first { it.getString("event") == "progression_start" }.getJSONObject("data")
        assertEquals(setOf("rs_kind", "rs_status", "rs_p1"), keys(s))
        assertEquals("progression", s.getString("rs_kind"))
        assertEquals("start", s.getString("rs_status"))
        val c = events.first { it.getString("event") == "progression_complete" }.getJSONObject("data")
        assertEquals(setOf("rs_kind", "rs_status", "rs_p1", "rs_p2", "rs_p3", "rs_score"), keys(c))
        assertEquals(1200, c.getInt("rs_score"))
        assertTrue(events.any { it.getString("event") == "oops" })
    }

    @Test
    fun `error cuts the message to 200 and the stack to 2000 and defaults the name`() {
        start()
        Ravensight.trackError("critical", "m".repeat(500), "s".repeat(5000))
        Ravensight.trackError("info", "short", eventName = "note")
        val events = flushed()
        val d = events.first { it.getString("event") == "error" }.getJSONObject("data")
        assertEquals(setOf("rs_kind", "rs_severity", "rs_message", "rs_stack"), keys(d))
        assertEquals("error", d.getString("rs_kind"))
        assertEquals("critical", d.getString("rs_severity"))
        assertEquals(200, d.getString("rs_message").length)
        assertEquals(2000, d.getString("rs_stack").length)
        val n = events.first { it.getString("event") == "note" }.getJSONObject("data")
        assertFalse(n.has("rs_stack"))
    }

    @Test
    fun `helpers return what track returns and never throw on bad input`() {
        start()
        assertFalse(Ravensight.trackPurchase(null, "", "", "", eventName = " "))
        assertTrue(Ravensight.trackPurchase(null, "", "", ""))
        assertTrue(Ravensight.trackResource("nope", "", Double.NaN))
    }

    // --- Reserved key check -----------------------------------------------

    @Test
    fun `typed kinds and keys do not warn, rs_invalid and unknown keys do`() {
        start(verbose = true)
        Ravensight.trackPurchase(1, "usd", "a", "b", cart = "c", transactionId = "t")
        Ravensight.trackResource("source", "g", 1, "t", "i")
        Ravensight.trackProgression("complete", "a", "b", "c", 1)
        Ravensight.trackError("error", "m", "s")
        Ravensight.track(
            "h",
            mapOf("rs_kind" to "health", "rs_fps_median" to 60, "rs_fps_p10" to 30, "rs_memory_mb" to 1, "rs_boot_ms" to 2),
        )
        fun warnings() = ShadowLog.getLogsForTag("Ravensight").filter { it.type == Log.WARN }.map { it.msg }
        assertEquals(emptyList<String>(), warnings())
        Ravensight.track("x", mapOf("rs_invalid" to "purchase_amount"))
        Ravensight.track("y", mapOf("rs_made_up" to 1))
        Ravensight.track("z", mapOf("rs_kind" to "boss"))
        assertEquals(3, warnings().size)
        assertTrue(warnings().any { it.contains("rs_invalid") })
    }

    @Test
    fun `RS_KINDS lists the level and the typed kinds`() {
        assertEquals(
            listOf("level_start", "level_complete", "death", "none", "purchase", "resource", "progression", "error", "health"),
            RavensightConfig.RS_KINDS,
        )
    }

    @Test
    fun `the session request carries the sdk name and version`() {
        start()
        val body = JSONObject(http.requests.first { it.path == "/session" }.body!!)
        assertEquals("android/0.3.1", body.getString("sdk"))
    }

    // --- Health ------------------------------------------------------------

    @Test
    fun `the summary takes the median and the 10th percentile from a known sample set`() {
        val s = FrameSampler()
        s.onFrame(0)
        var t = 0L
        for (fps in listOf(50, 10, 90, 30, 70, 20, 100, 40, 80, 60)) t = feedWindow(s, t, fps)
        val h = s.summary()!!
        assertEquals(10, s.sampleCount)
        assertEquals(55.0, h.fpsMedian, 0.01)
        assertEquals(10.0, h.fpsP10, 0.01)
    }

    @Test
    fun `no samples means no summary`() {
        val s = FrameSampler()
        assertNull(s.summary())
        s.onFrame(0)
        s.onFrame(500_000_000L)
        assertNull(s.summary())
    }

    @Test
    fun `the reservoir never holds more than 600 samples`() {
        val s = FrameSampler()
        s.onFrame(0)
        var t = 0L
        repeat(2000) { t = feedWindow(s, t, 1) }
        assertEquals(600, s.sampleCount)
        assertEquals(1.0, s.summary()!!.fpsMedian, 0.01)
    }

    @Test
    fun `one health event per session is sent on background entry with every field`() {
        start(performance = true)
        feed(listOf(60, 60, 30, 60, 60))
        background()
        val health = flushed().filter { it.getString("event") == "health" }
        assertEquals(1, health.size)
        val d = health[0].getJSONObject("data")
        assertEquals(setOf("rs_kind", "rs_fps_median", "rs_fps_p10", "rs_memory_mb", "rs_boot_ms"), keys(d))
        assertEquals("health", d.getString("rs_kind"))
        assertEquals(60.0, d.getDouble("rs_fps_median"), 0.01)
        assertEquals(30.0, d.getDouble("rs_fps_p10"), 0.01)
        assertEquals(128.5, d.getDouble("rs_memory_mb"), 0.0)
        assertEquals(900, d.getInt("rs_boot_ms"))

        // Back within the session timeout, more samples, another background: still one.
        foreground()
        feed(listOf(10, 10))
        background()
        assertEquals(1, flushed().count { it.getString("event") == "health" })
    }

    @Test
    fun `a new session after the timeout sends a new health event`() {
        start(performance = true)
        feed(listOf(60, 60))
        background()
        now += 31 * 60_000L
        foreground()
        feed(listOf(40, 40))
        background()
        val health = flushed().filter { it.getString("event") == "health" }
        assertEquals(2, health.size)
        assertEquals(40.0, health[1].getJSONObject("data").getDouble("rs_fps_median"), 0.01)
    }

    @Test
    fun `nothing is sent with no samples or with performance off`() {
        start(performance = true)
        background()
        assertTrue(flushed().none { it.getString("event") == "health" })
        Ravensight.resetForTest()
        http.requests.clear()
        start(performance = false)
        feed(listOf(60, 60))
        background()
        assertTrue(flushed().none { it.getString("event") == "health" })
    }

    // --- Real frame callback gating, opt-out and teardown ---------------------

    @Test
    fun `the frame callback runs per foreground and a second session sends a second health event`() {
        val fs = FakeScheduler()
        start(performance = true, scheduler = fs)
        assertTrue(fs.pending != null)
        fs.frames(4)
        background()
        assertEquals(null, fs.pending)
        assertEquals(1, flushed().count { it.getString("event") == "health" })

        now += 31 * 60_000L
        foreground()
        assertTrue("sampling must restart for the new session", fs.pending != null)
        fs.frames(4)
        background()
        assertEquals(null, fs.pending)
        assertEquals(2, flushed().count { it.getString("event") == "health" })
    }

    @Test
    fun `tracking off stops the callback, keeps it stopped, and turning it back on restarts it`() {
        val fs = FakeScheduler()
        start(performance = true, scheduler = fs)
        assertTrue(fs.pending != null)
        Ravensight.setTrackingEnabled(app, false)
        assertEquals(null, fs.pending)
        background()
        foreground()
        assertEquals(null, fs.pending)
        Ravensight.setTrackingEnabled(app, true)
        assertTrue(fs.pending != null)
    }

    @Test
    fun `the game switch off stops the callback too`() {
        val fs = FakeScheduler()
        start(performance = true, scheduler = fs)
        Ravensight.setEnabled(false)
        assertEquals(null, fs.pending)
    }

    @Test
    fun `teardown removes the frame callback`() {
        val fs = FakeScheduler()
        start(performance = true, scheduler = fs)
        assertTrue(fs.pending != null)
        Ravensight.resetForTest()
        assertEquals(null, fs.pending)
    }

    @Test
    fun `with tracking disabled every typed helper queues nothing and the transport sees no request`() {
        start()
        Ravensight.setTrackingEnabled(app, false)
        worker.runDue()
        val before = http.requests.size
        assertFalse(Ravensight.trackPurchase(499, "usd", "a", "b"))
        assertFalse(Ravensight.trackResource("source", "gold", 1))
        assertFalse(Ravensight.trackProgression("start", "w"))
        assertFalse(Ravensight.trackError("error", "m"))
        Ravensight.flush()
        worker.runDue()
        assertEquals(before, http.requests.size)
        assertTrue(http.requests.none { it.body?.contains("rs_kind") == true })
    }

    @Test
    fun `with performance on and tracking disabled, frames then background queue nothing`() {
        val fs = FakeScheduler()
        start(performance = true, scheduler = fs)
        Ravensight.setTrackingEnabled(app, false)
        worker.runDue()
        fs.frames(5)
        Ravensight.controllerForTest!!.frameSampler.let { s ->
            var t = 0L
            s.onFrame(t)
            repeat(3) { t += 1_000_000_000L; s.onFrame(t) }
        }
        val before = http.requests.size
        background()
        Ravensight.flush()
        worker.runDue()
        assertEquals(before, http.requests.size)
        assertTrue(http.requests.none { it.body?.contains("\"health\"") == true })
    }
}
