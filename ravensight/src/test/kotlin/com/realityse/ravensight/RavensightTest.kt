package com.realityse.ravensight

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Drives the Android layer (Ravensight.kt) on Robolectric with a fake
 * transport, a fake clock and a manual worker queue, so every message runs
 * on the test thread in order and time only moves when a test moves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RavensightTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private val worker = ManualWorker { now }
    private val http = FakeTransport()
    private val listener = RecordingListener()
    private var env = mapOf<String, String>()

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

    // --- Harness ------------------------------------------------------------

    private fun config(
        enabled: Boolean = true,
        trackPauseEvents: Boolean = false,
        playtestToken: String? = null,
        playtestRunId: String? = null,
        ingestKey: String = "gt_live_test",
        verbose: Boolean = false,
    ) = RavensightConfig(
        ingestKey = ingestKey,
        apiUrl = "https://api.example.test/",
        gameVersion = "1.0.0",
        enabled = enabled,
        trackPauseEvents = trackPauseEvents,
        playtestToken = playtestToken,
        playtestRunId = playtestRunId,
        verboseLogging = verbose,
    )

    private fun init(cfg: RavensightConfig = config(), activityCallbacks: Boolean = false) {
        Ravensight.initialize(
            app, cfg, listener,
            RavensightDeps(
                transport = http,
                clockMs = { now },
                worker = worker,
                mainPost = { it.run() },
                env = { env[it] },
                observeProcessLifecycle = false,
                registerActivityCallbacks = activityCallbacks,
            ),
        )
        worker.runDue()
    }

    private val controller get() = Ravensight.controllerForTest!!

    private fun foreground() {
        controller.onForegroundSignal()
        worker.runDue()
    }

    private fun background() {
        controller.onBackgroundSignal()
        worker.runDue()
    }

    private fun advance(ms: Long) {
        now += ms
        worker.runDue()
    }

    private fun prefs() = app.getSharedPreferences(Ravensight.PREFS, Context.MODE_PRIVATE)

    private fun batchEvents(): List<JSONObject> = http.requests
        .filter { it.path == "/track/batch" }
        .flatMap { r ->
            val arr = JSONObject(r.body!!).getJSONArray("events")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

    private fun eventNames() = batchEvents().map { it.getString("event") }

    // --- X1: never throw into the game ------------------------------------

    @Test
    fun `a blank event name returns false, never throws and sends nothing`() {
        init()
        foreground()
        val before = http.requests.size
        assertFalse(Ravensight.track(""))
        assertFalse(Ravensight.track("   "))
        Ravensight.flush()
        worker.runDue()
        assertEquals(before, http.requests.size)
        assertTrue(ShadowLog.getLogsForTag("Ravensight").any { it.msg.contains("blank event name") })
    }

    @Test
    fun `an exception inside a worker message is caught and the worker keeps going`() {
        init()
        http.throwOn = "/settings"
        foreground() // would crash the looper if it escaped
        http.throwOn = null
        assertTrue(ShadowLog.getLogsForTag("Ravensight").any { it.msg.contains("failed inside the SDK") })
        Ravensight.flush()
        worker.runDue()
        assertTrue(http.paths().contains("/session"))
    }

    @Test
    fun `a blank ingest key never throws and never touches the network`() {
        init(config(ingestKey = " "))
        foreground()
        Ravensight.track("e")
        Ravensight.flush()
        worker.runDue()
        assertEquals(0, http.requests.size)
        assertEquals("invalid_key", Ravensight.statusReason)
    }

    @Test
    fun `NaN and infinite values are sent as null rather than failing the batch`() {
        init()
        foreground()
        Ravensight.track("odd", mapOf("nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY, "ok" to 1.5))
        Ravensight.flush()
        worker.runDue()
        val data = batchEvents().last { it.getString("event") == "odd" }.getJSONObject("data")
        assertTrue(data.isNull("nan"))
        assertTrue(data.isNull("inf"))
        assertEquals(1.5, data.getDouble("ok"), 0.0)
    }

    // --- Opt-out in every order -------------------------------------------

    @Test
    fun `enabled false makes zero requests of any kind`() {
        init(config(enabled = false))
        foreground()
        assertFalse(Ravensight.track("e"))
        Ravensight.flush()
        Ravensight.submitFeedback("hi", "bug", 3)
        Ravensight.fetchSuggestions()
        advance(60_000)
        assertEquals(0, http.requests.size)
        assertEquals(listOf("disabled"), listener.feedbackFailed)
        assertEquals(listOf(emptyList<Any?>()), listener.suggestions)
        assertEquals("disabled", Ravensight.statusReason)
    }

    @Test
    fun `setEnabled before initialize is held and applied`() {
        Ravensight.setEnabled(false)
        init()
        foreground()
        advance(10_000)
        assertEquals(0, http.requests.size)
        Ravensight.setEnabled(true)
        worker.runDue()
        assertEquals(listOf("/settings", "/session", "/track/batch"), http.paths())
        assertEquals(listOf("game_started"), eventNames())
    }

    @Test
    fun `an opt-out before initialize makes zero requests`() {
        Ravensight.setTrackingEnabled(app, false)
        assertFalse(Ravensight.isTrackingEnabled(app))
        init()
        foreground()
        assertFalse(Ravensight.track("e"))
        Ravensight.flush()
        advance(60_000)
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `a saved opt-out holds across a new client`() {
        init()
        Ravensight.setTrackingEnabled(app, false)
        worker.runDue()
        Ravensight.resetForTest() // a new launch
        init()
        foreground()
        Ravensight.track("e")
        advance(60_000)
        assertEquals(0, http.requests.size)
        assertTrue(prefs().getBoolean(Ravensight.KEY_OPT_OUT, false))
    }

    @Test
    fun `an opt-out right after the foreground signal wins before any request`() {
        init()
        controller.onForegroundSignal()
        Ravensight.setTrackingEnabled(app, false) // before the worker runs
        worker.runDue()
        advance(60_000)
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `opting out clears the queue, stops the timer and tells the listener`() {
        init()
        foreground()
        http.requests.clear()
        http.batchStatus = 500 // keep events queued
        Ravensight.track("a")
        Ravensight.setTrackingEnabled(app, false)
        worker.runDue()
        http.batchStatus = 202
        advance(600_000)
        assertEquals(0, http.requests.size)
        assertEquals(listOf("player"), listener.trackingDisabled)
        // Turning it back on resumes, without a second game_started.
        Ravensight.setTrackingEnabled(app, true)
        Ravensight.track("b")
        Ravensight.flush()
        worker.runDue()
        assertEquals(listOf("b"), eventNames())
    }

    @Test
    fun `resetDeviceId mints a new saved id, clears the queue and opens a new session`() {
        init()
        foreground()
        val old = Ravensight.deviceId!!
        http.batchStatus = 500
        Ravensight.track("queued_under_old_id")
        worker.runDue()
        http.batchStatus = 202
        val fresh = Ravensight.resetDeviceId()
        worker.runDue()
        assertNotEquals(old, fresh)
        assertTrue(Regex("dev_[0-9a-f]{32}").matches(fresh))
        assertEquals(fresh, prefs().getString(Ravensight.KEY_DEVICE_ID, null))
        http.requests.clear()
        Ravensight.track("after")
        Ravensight.flush()
        worker.runDue()
        assertEquals(listOf("/session", "/track/batch"), http.paths())
        assertEquals(fresh, JSONObject(http.requests[0].body!!).getString("deviceId"))
        assertEquals(listOf("after"), eventNames())
    }

    @Test
    fun `resetDeviceId with a context works before initialize`() {
        val id = Ravensight.resetDeviceId(app)
        init()
        foreground()
        assertEquals(id, Ravensight.deviceId)
    }

    // --- Lifecycle and the mobile session model ---------------------------

    @Test
    fun `a background process start sends nothing until the first foreground`() {
        init()
        Ravensight.track("from_a_push_handler")
        Ravensight.flush()
        Ravensight.fetchSuggestions()
        advance(60_000)
        assertEquals(0, http.requests.size)
        foreground()
        assertEquals(listOf("/settings", "/session", "/track/batch"), http.paths())
        // game_started opens the session, ahead of anything tracked before the foreground.
        assertEquals(listOf("game_started", "from_a_push_handler"), eventNames())
    }

    @Test
    fun `game_started is first, stamped at the foreground, and sent once`() {
        init()
        val startedAt = now
        foreground()
        Ravensight.setEnabled(false)
        Ravensight.setEnabled(true)
        foreground() // a second foreground signal with no background between
        advance(10_000)
        val started = batchEvents().filter { it.getString("event") == "game_started" }
        assertEquals(1, started.size)
        assertEquals(startedAt / 1000, started[0].getLong("timestamp"))
        assertEquals(listOf("/settings", "/session", "/track/batch"), http.paths())
    }

    @Test
    fun `a client enabled later in the run queues game_started then`() {
        init(config(enabled = false))
        foreground()
        assertEquals(0, http.requests.size)
        Ravensight.setEnabled(true)
        worker.runDue()
        assertEquals(listOf("game_started"), eventNames())
    }

    @Test
    fun `under 30 minutes in the background nothing is queued and the session continues`() {
        init()
        foreground()
        http.requests.clear()
        background()
        assertTrue(prefs().contains(Ravensight.KEY_MARKER_TOKEN))
        advance(29 * 60_000L)
        foreground()
        advance(10_000)
        assertEquals(0, http.requests.size)
        assertFalse(prefs().contains(Ravensight.KEY_MARKER_TOKEN))
    }

    @Test
    fun `over 30 minutes in the background ends the session with game_exited at background time`() {
        init()
        foreground()
        http.requests.clear()
        val backgroundedAt = now
        background()
        advance(31 * 60_000L)
        foreground()
        assertEquals(listOf("/track/batch", "/session", "/track/batch"), http.paths())
        val exited = JSONObject(http.requests[0].body!!).getJSONArray("events").getJSONObject(0)
        assertEquals("game_exited", exited.getString("event"))
        assertEquals(backgroundedAt / 1000, exited.getLong("timestamp"))
        assertEquals("tok_1", http.requests[0].headers["X-Session-Token"])
        assertEquals("tok_2", http.requests[2].headers["X-Session-Token"])
        val started = JSONObject(http.requests[2].body!!).getJSONArray("events").getJSONObject(0)
        assertEquals("game_started", started.getString("event"))
        assertEquals(now / 1000, started.getLong("timestamp"))
        assertFalse(prefs().contains(Ravensight.KEY_MARKER_TOKEN))
    }

    @Test
    fun `a failed game_exited after the rollover is dropped, never sent on the new session`() {
        init()
        foreground()
        http.requests.clear()
        background()
        advance(31 * 60_000L)
        http.failTokenOnce = "tok_1" // the old session's exit answers status 0
        foreground()
        advance(10 * 60_000L)
        val exitedOnOld = http.requests.filter {
            it.path == "/track/batch" && it.headers["X-Session-Token"] == "tok_1"
        }
        assertEquals(1, exitedOnOld.size)
        val onNew = http.requests.filter { it.path == "/track/batch" && it.headers["X-Session-Token"] != "tok_1" }
        assertTrue(onNew.isNotEmpty())
        for (req in onNew) assertFalse(req.body!!.contains("game_exited"))
        assertEquals(listOf("game_exited", "game_started"), eventNames()) // the exit once, on tok_1 only
    }

    @Test
    fun `the old session's leftovers go out before its game_exited`() {
        init()
        foreground()
        background()
        http.requests.clear()
        http.batchStatus = 500
        Ravensight.track("late") // fails once while backgrounded
        Ravensight.flush()
        worker.runDue()
        http.batchStatus = 202
        advance(31 * 60_000L)
        foreground()
        val onOld = http.requests.filter { it.headers["X-Session-Token"] == "tok_1" }.map {
            JSONObject(it.body!!).getJSONArray("events").getJSONObject(0).getString("event")
        }
        assertEquals("game_exited", onOld.last())
        assertTrue(http.requests.filter { it.headers["X-Session-Token"] == "tok_2" }.none { it.body!!.contains("game_exited") })
    }

    @Test
    fun `an opt-out right before the rollover foreground sends nothing`() {
        init()
        foreground()
        background()
        http.requests.clear()
        advance(31 * 60_000L)
        controller.onForegroundSignal()
        Ravensight.setTrackingEnabled(app, false)
        worker.runDue()
        advance(60_000)
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `a settings 429 waits Retry-After and checks again`() {
        http.settingsStatus = 429
        http.retryAfter = "30"
        init()
        foreground()
        assertEquals(listOf("/settings"), http.paths())
        assertEquals("rate_limited", Ravensight.statusReason)
        http.settingsStatus = 200
        http.retryAfter = null
        advance(29_999)
        assertEquals(1, http.requests.size)
        advance(1)
        assertEquals(listOf("/settings", "/settings", "/session", "/track/batch"), http.paths())
        assertEquals(null, Ravensight.statusReason)
    }

    @Test
    fun `data that cannot be serialized fails track on the caller and blocks nothing`() {
        init()
        foreground()
        http.requests.clear()
        val loop = HashMap<String, Any?>()
        loop["self"] = loop
        val bad = object {
            override fun toString(): String = throw IllegalStateException("poison")
        }
        assertFalse(Ravensight.track("loop", loop))
        assertFalse(Ravensight.track("bad", mapOf("v" to bad)))
        assertTrue(Ravensight.track("fine", mapOf("v" to 1)))
        Ravensight.flush()
        worker.runDue()
        assertEquals(listOf("fine"), eventNames())
    }

    @Test
    fun `event data is copied when tracked`() {
        init()
        foreground()
        http.requests.clear()
        val data = hashMapOf<String, Any?>("score" to 1, "tags" to mutableListOf("a"))
        Ravensight.track("e", data)
        data["score"] = 2
        @Suppress("UNCHECKED_CAST")
        (data["tags"] as MutableList<String>).add("b")
        Ravensight.flush()
        worker.runDue()
        val sent = batchEvents().single().getJSONObject("data")
        assertEquals(1, sent.getInt("score"))
        assertEquals(1, sent.getJSONArray("tags").length())
    }

    @Test
    fun `pause and resume events are opt-in`() {
        init(config(trackPauseEvents = true))
        foreground()
        background()
        advance(60_000)
        foreground()
        Ravensight.flush()
        worker.runDue()
        assertEquals(listOf("game_started", "game_paused", "game_resumed"), eventNames())
    }

    @Test
    fun `by default backgrounding and returning send no pause or resume events`() {
        init()
        foreground()
        background()
        advance(60_000)
        foreground()
        Ravensight.flush()
        worker.runDue()
        assertEquals(listOf("game_started"), eventNames())
    }

    @Test
    fun `a cold launch with a marker sends the last session's game_exited first`() {
        prefs().edit()
            .putString(Ravensight.KEY_MARKER_TOKEN, "tok_old")
            .putLong(Ravensight.KEY_MARKER_EXPIRES_AT, now + 3_600_000L)
            .putLong(Ravensight.KEY_MARKER_BACKGROUNDED_AT, now - 120_000L)
            .commit()
        init()
        assertEquals(0, http.requests.size) // not before the foreground
        foreground()
        val first = http.requests[0]
        assertEquals("/track/batch", first.path)
        assertEquals("tok_old", first.headers["X-Session-Token"])
        val events = JSONObject(first.body!!).getJSONArray("events")
        assertEquals(1, events.length())
        assertEquals("game_exited", events.getJSONObject(0).getString("event"))
        assertEquals((now - 120_000L) / 1000, events.getJSONObject(0).getLong("timestamp"))
        assertEquals(0, events.getJSONObject(0).getJSONObject("data").length())
        assertEquals(listOf("/track/batch", "/settings", "/session", "/track/batch"), http.paths())
        assertFalse(prefs().contains(Ravensight.KEY_MARKER_TOKEN))
    }

    @Test
    fun `a cold launch marker is deleted without a request when expired or opted out`() {
        prefs().edit()
            .putString(Ravensight.KEY_MARKER_TOKEN, "tok_old")
            .putLong(Ravensight.KEY_MARKER_EXPIRES_AT, now - 1L)
            .putLong(Ravensight.KEY_MARKER_BACKGROUNDED_AT, now - 120_000L)
            .commit()
        init()
        foreground()
        assertFalse(http.requests.any { it.headers["X-Session-Token"] == "tok_old" })
        assertFalse(prefs().contains(Ravensight.KEY_MARKER_TOKEN))

        Ravensight.resetForTest()
        http.requests.clear()
        prefs().edit()
            .putString(Ravensight.KEY_MARKER_TOKEN, "tok_old")
            .putLong(Ravensight.KEY_MARKER_EXPIRES_AT, now + 3_600_000L)
            .putLong(Ravensight.KEY_MARKER_BACKGROUNDED_AT, now - 120_000L)
            .putBoolean(Ravensight.KEY_OPT_OUT, true)
            .commit()
        init()
        foreground()
        assertEquals(0, http.requests.size)
        assertFalse(prefs().contains(Ravensight.KEY_MARKER_TOKEN))
    }

    @Test
    fun `a cold launch marker gets one attempt and no retry`() {
        prefs().edit()
            .putString(Ravensight.KEY_MARKER_TOKEN, "tok_old")
            .putLong(Ravensight.KEY_MARKER_EXPIRES_AT, now + 3_600_000L)
            .putLong(Ravensight.KEY_MARKER_BACKGROUNDED_AT, now - 120_000L)
            .commit()
        http.failTokenOnce = "tok_old"
        init()
        foreground()
        advance(600_000)
        assertEquals(1, http.requests.count { it.headers["X-Session-Token"] == "tok_old" })
    }

    @Test
    fun `rotation in activity callback mode sends nothing`() {
        init(config(trackPauseEvents = true), activityCallbacks = true)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        worker.runDue()
        assertEquals(listOf("/settings", "/session", "/track/batch"), http.paths())
        http.requests.clear()
        val rotated = Configuration(app.resources.configuration).apply {
            orientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        }
        activity.configurationChange(rotated)
        worker.runDue()
        Ravensight.flush()
        worker.runDue()
        assertEquals(0, http.requests.size)
        assertFalse(prefs().contains(Ravensight.KEY_MARKER_TOKEN))
        // A real background is still seen.
        activity.pause().stop()
        worker.runDue()
        assertEquals(listOf("game_paused"), eventNames())
    }

    // --- Failures and the status reason -----------------------------------

    @Test
    fun `settings 401 makes one request then none and logs once`() {
        http.settingsStatus = 401
        init()
        foreground()
        advance(60 * 60_000L)
        assertEquals(listOf("/settings"), http.paths())
        assertEquals("invalid_key", Ravensight.statusReason)
        val logs = ShadowLog.getLogsForTag("Ravensight").filter {
            it.msg == "Ravensight: ingest key refused (invalid_key), sending stopped for this run. Check your gt_live_ key."
        }
        assertEquals(1, logs.size)
        assertTrue(listener.statuses.contains("invalid_key"))
        // An explicit flush makes exactly one more attempt.
        Ravensight.flush()
        worker.runDue()
        assertEquals(listOf("/settings", "/settings"), http.paths())
        advance(60 * 60_000L)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `session 403 tracking_disabled stops and reports the kill switch`() {
        http.sessionStatus = 403
        http.sessionError = "tracking_disabled"
        init()
        foreground()
        Ravensight.track("later")
        advance(60 * 60_000L)
        assertEquals(listOf("/settings", "/session"), http.paths())
        assertEquals(listOf("server"), listener.trackingDisabled)
        assertEquals("tracking_disabled", Ravensight.statusReason)
        assertFalse(Ravensight.isEnabled)
    }

    @Test
    fun `settings kill switch makes no further requests`() {
        http.trackingEnabled = false
        init()
        foreground()
        Ravensight.track("e")
        Ravensight.fetchSuggestions()
        advance(60 * 60_000L)
        assertEquals(listOf("/settings"), http.paths())
        assertEquals(listOf(emptyList<Any?>()), listener.suggestions)
    }

    @Test
    fun `batch 429 waits exactly Retry-After`() {
        init()
        foreground()
        http.requests.clear()
        http.batchStatus = 429
        http.retryAfter = "7"
        Ravensight.track("e")
        Ravensight.flush()
        worker.runDue()
        assertEquals(1, http.requests.size)
        http.batchStatus = 202
        advance(6_999)
        assertEquals(1, http.requests.size)
        advance(1)
        assertEquals(2, http.requests.size)
        assertEquals(null, Ravensight.statusReason)
    }

    @Test
    fun `fifty queued events send at once without waiting for the timer`() {
        init(RavensightConfig(ingestKey = "gt_live_test", flushIntervalMs = 0))
        foreground()
        http.requests.clear()
        repeat(49) { Ravensight.track("e$it") }
        worker.runDue()
        assertEquals(0, http.requests.size)
        Ravensight.track("e49")
        worker.runDue()
        assertEquals(1, http.requests.size)
        assertEquals(50, JSONObject(http.requests[0].body!!).getJSONArray("events").length())
    }

    @Test
    fun `the timer flushes every five seconds`() {
        init()
        foreground()
        http.requests.clear()
        Ravensight.track("e")
        worker.runDue()
        assertEquals(0, http.requests.size)
        advance(5_000)
        assertEquals(1, http.requests.size)
    }

    // --- Feedback, suggestions, session hand-off ---------------------------

    @Test
    fun `feedback is validated locally with no request`() {
        init()
        foreground()
        http.requests.clear()
        Ravensight.submitFeedback("hi", "wishlist", 0)
        Ravensight.submitFeedback("hi", "bug", 6)
        Ravensight.submitFeedback("hi", "bug", -1)
        worker.runDue()
        assertEquals(0, http.requests.size)
        assertEquals(listOf("invalid_category", "invalid_rating", "invalid_rating"), listener.feedbackFailed)
        assertEquals(
            listOf("bug", "suggestion", "complaint", "praise", "playtest", "other"),
            RavensightConfig.FEEDBACK_CATEGORIES,
        )
    }

    @Test
    fun `valid feedback is sent with its category and rating`() {
        init()
        foreground()
        http.requests.clear()
        Ravensight.submitFeedback("More checkpoints please", "suggestion", 4)
        worker.runDue()
        assertEquals(listOf("/feedback"), http.paths())
        val body = JSONObject(http.requests[0].body!!)
        assertEquals("suggestion", body.getString("category"))
        assertEquals(4, body.getInt("rating"))
        assertEquals(1, listener.feedbackSubmitted)
    }

    @Test
    fun `a feedback 401 invalidates the session and opens a new one`() {
        init()
        foreground()
        http.requests.clear()
        http.feedbackStatus = 401
        Ravensight.submitFeedback("hi")
        worker.runDue()
        assertEquals(listOf("/feedback", "/session"), http.paths())
        assertEquals("tok_2", Ravensight.sessionToken)
    }

    @Test
    fun `sessionToken is null until a session opens`() {
        init()
        assertNull(Ravensight.sessionToken)
        foreground()
        assertEquals("tok_1", Ravensight.sessionToken)
        assertTrue(Ravensight.isReady)
    }

    @Test
    fun `an unknown rs_kind warns in verbose mode and still queues`() {
        init(config(verbose = true))
        foreground()
        assertTrue(Ravensight.track("boss", mapOf("rs_kind" to "boss_fight")))
        assertTrue(Ravensight.track("boss", mapOf("rs_kind" to "boss_fight")))
        assertTrue(Ravensight.track("lvl", mapOf("rs_kind" to "level_start")))
        val warnings = ShadowLog.getLogsForTag("Ravensight").filter { it.msg.contains("rs_kind") }
        assertEquals(1, warnings.size)
    }

    // --- Playtest ---------------------------------------------------------

    private fun setDebuggable(debuggable: Boolean) {
        val pm = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!
        for (info in listOf(app.applicationInfo, pm)) {
            info.flags = if (debuggable) {
                info.flags or ApplicationInfo.FLAG_DEBUGGABLE
            } else {
                info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            }
        }
    }

    private fun setAllowMeta(allow: Boolean?) {
        val bundle = allow?.let { Bundle().apply { putBoolean(Ravensight.META_ALLOW_PLAYTEST_INTENT, it) } }
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!.metaData = bundle
        app.applicationInfo.metaData = bundle
    }

    private fun launchWithPlaytestExtras() {
        val intent = Intent(app, Activity::class.java)
            .putExtra("ravensight_playtest_token", "ptok")
            .putExtra("ravensight_playtest_run_id", "run42")
        Robolectric.buildActivity(Activity::class.java, intent).create()
        worker.runDue()
    }

    @Test
    fun `playtest extras are ignored on a release build without the meta-data`() {
        setDebuggable(false)
        setAllowMeta(null)
        prefs().edit().putBoolean(Ravensight.KEY_OPT_OUT, true).commit()
        init(activityCallbacks = true)
        launchWithPlaytestExtras()
        foreground()
        advance(60_000)
        assertFalse(Ravensight.isPlaytest)
        assertEquals(0, http.requests.size) // the opt-out still holds
        assertTrue(ShadowLog.getLogsForTag("Ravensight").any { it.msg.contains("playtest intent extras ignored") })
    }

    @Test
    fun `playtest extras are ignored on a release build with the meta-data false`() {
        setDebuggable(false)
        setAllowMeta(false)
        init(activityCallbacks = true)
        launchWithPlaytestExtras()
        foreground()
        assertFalse(Ravensight.isPlaytest)
        assertTrue(http.requests.none { it.headers.containsKey("X-Ravensight-Playtest") })
        assertTrue(Ravensight.deviceId!!.startsWith("dev_"))
    }

    @Test
    fun `playtest extras are honoured on a release build with the meta-data`() {
        setDebuggable(false)
        setAllowMeta(true)
        init(activityCallbacks = true)
        launchWithPlaytestExtras()
        foreground()
        assertTrue(Ravensight.isPlaytest)
        assertEquals("pt-run42", Ravensight.deviceId)
        assertTrue(http.requests.all { it.headers["X-Ravensight-Playtest"] == "ptok" })
    }

    @Test
    fun `playtest extras give a pt id, the header everywhere and nothing saved`() {
        setDebuggable(true)
        setAllowMeta(null)
        prefs().edit().putBoolean(Ravensight.KEY_OPT_OUT, true).commit() // ignored for the run
        init(activityCallbacks = true)
        launchWithPlaytestExtras()
        foreground()
        assertTrue(Ravensight.isPlaytest)
        assertEquals("pt-run42", Ravensight.deviceId)
        assertEquals(listOf("/settings", "/session", "/track/batch"), http.paths())
        assertTrue(http.requests.all { it.headers["X-Ravensight-Playtest"] == "ptok" })
        assertEquals("pt-run42", JSONObject(http.requests[1].body!!).getString("deviceId"))
        assertFalse(prefs().contains(Ravensight.KEY_DEVICE_ID))
        // Opt-out and reset are no-ops that write nothing.
        Ravensight.setTrackingEnabled(app, true)
        assertEquals("pt-run42", Ravensight.resetDeviceId())
        assertFalse(prefs().contains(Ravensight.KEY_DEVICE_ID))
        assertTrue(prefs().getBoolean(Ravensight.KEY_OPT_OUT, false))
        // A closed job stops the run.
        http.batchStatus = 403
        http.batchError = "playtest_job_closed"
        Ravensight.track("e")
        Ravensight.flush()
        worker.runDue()
        val count = http.requests.size
        Ravensight.track("again")
        advance(60 * 60_000L)
        assertEquals(count, http.requests.size)
        assertEquals("playtest_job_closed", Ravensight.statusReason)
    }

    @Test
    fun `a playtest token without a run id never runs as a real player`() {
        init(config(playtestToken = "ptok"))
        foreground()
        Ravensight.track("e")
        advance(60_000)
        assertEquals(0, http.requests.size)
        assertEquals("session_failed", Ravensight.statusReason)
        assertFalse(prefs().contains(Ravensight.KEY_DEVICE_ID))
    }

    @Test
    fun `the playtest environment variables are the last resort`() {
        env = mapOf("RAVENSIGHT_PLAYTEST_TOKEN" to "envtok", "RAVENSIGHT_PLAYTEST_RUN_ID" to "r9")
        init()
        foreground()
        assertEquals("pt-r9", Ravensight.deviceId)
        assertTrue(http.requests.all { it.headers["X-Ravensight-Playtest"] == "envtok" })
    }

    @Test
    fun `config wins over the environment`() {
        env = mapOf("RAVENSIGHT_PLAYTEST_TOKEN" to "envtok", "RAVENSIGHT_PLAYTEST_RUN_ID" to "r9")
        init(config(playtestToken = "cfgtok", playtestRunId = "r1"))
        foreground()
        assertEquals("pt-r1", Ravensight.deviceId)
        assertTrue(http.requests.all { it.headers["X-Ravensight-Playtest"] == "cfgtok" })
    }

    // --- Pure helpers -----------------------------------------------------

    @Test
    fun `apiUrl forms all normalise to one base`() {
        val expected = "https://api.ravensight.io/api/v1"
        for (form in listOf(
            "https://api.ravensight.io",
            "https://api.ravensight.io/",
            "https://api.ravensight.io//",
            "https://api.ravensight.io/api/v1",
            "https://api.ravensight.io/api/v1/",
            "  https://api.ravensight.io/api/v1  ",
            "",
            "   ",
        )) {
            assertEquals(form, expected, RavensightConfig.normalizeApiUrl(form))
        }
        assertEquals("https://api.ravensight.io", RavensightConfig.DEFAULT_API_URL)
        assertEquals("http://localhost:3000/api/v1", RavensightConfig.normalizeApiUrl("http://localhost:3000"))
    }

    @Test
    fun `requests go to the normalised base`() {
        init()
        foreground()
        assertTrue(http.requests.all { it.url.startsWith("https://api.example.test/api/v1/") })
    }

    @Test
    fun `RavensightJson converts nested values both ways`() {
        val value = RavensightJson.toJsonValue(
            mapOf(
                "s" to "x", "i" to 3, "b" to true, "n" to null,
                "list" to listOf(1, "two", mapOf("k" to 2.5)),
                "arr" to arrayOf("a", "b"),
                "other" to StringBuilder("sb"),
            ),
        ) as JSONObject
        assertEquals("x", value.getString("s"))
        assertEquals(3, value.getInt("i"))
        assertTrue(value.getBoolean("b"))
        assertTrue(value.isNull("n"))
        assertEquals(2.5, value.getJSONArray("list").getJSONObject(2).getDouble("k"), 0.0)
        assertEquals("b", value.getJSONArray("arr").getString(1))
        assertEquals("sb", value.getString("other"))
        val plain = RavensightJson.toPlain(JSONArray("[{\"a\":[1,null]}]")) as List<*>
        assertEquals(mapOf("a" to listOf(1, null)), plain[0])
    }

    @Test
    fun `the version constant is 0_2_0`() {
        assertEquals("0.2.0", Ravensight.VERSION)
    }
}

// --- Fakes ------------------------------------------------------------------

/** Runs posted messages on the test thread in time order, only when the fake clock reaches them. */
internal class ManualWorker(private val clock: () -> Long) : RavensightWorker {
    private class Task(val at: Long, val seq: Long, val r: Runnable)
    private val tasks = mutableListOf<Task>()
    private var seq = 0L

    override fun post(r: Runnable) {
        tasks += Task(clock(), seq++, r)
    }

    override fun postDelayed(r: Runnable, delayMs: Long) {
        tasks += Task(clock() + delayMs, seq++, r)
    }

    override fun remove(r: Runnable) {
        tasks.removeAll { it.r === r }
    }

    fun runDue() {
        var steps = 0
        while (true) {
            val next = tasks.filter { it.at <= clock() }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: return
            tasks.remove(next)
            next.r.run()
            check(++steps < 10_000) { "worker did not settle" }
        }
    }
}

internal class FakeTransport : RavensightTransport {
    class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String?) {
        val path: String get() = url.substringAfter("/api/v1")
    }

    val requests = mutableListOf<Request>()
    var settingsStatus = 200
    var trackingEnabled = true
    var sessionStatus = 201
    var sessionError: String? = null
    var batchStatus = 202
    var batchError: String? = null
    var feedbackStatus = 201
    var retryAfter: String? = null
    var throwOn: String? = null
    var failTokenOnce: String? = null
    private var sessions = 0

    fun paths() = requests.map { it.path }

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val r = Request(method, url, headers, body)
        requests += r
        if (throwOn != null && r.path == throwOn) throw IllegalStateException("boom")
        return when (r.path) {
            "/settings" -> if (settingsStatus == 200) {
                HttpResult(200, "{\"trackingEnabled\":$trackingEnabled}", null)
            } else {
                HttpResult(settingsStatus, "{\"error\":\"invalid_api_key\"}", retryAfter)
            }
            "/session" -> if (sessionStatus == 201) {
                sessions++
                HttpResult(201, "{\"token\":\"tok_$sessions\",\"expiresIn\":86400}", null)
            } else {
                HttpResult(sessionStatus, "{\"error\":\"${sessionError ?: "nope"}\"}", retryAfter)
            }
            "/track/batch" -> {
                if (failTokenOnce != null && headers["X-Session-Token"] == failTokenOnce) {
                    failTokenOnce = null
                    return HttpResult(0, "", null)
                }
                val error = batchError?.let { "{\"error\":\"$it\"}" } ?: "{}"
                HttpResult(batchStatus, error, retryAfter)
            }
            "/feedback" -> HttpResult(feedbackStatus, if (feedbackStatus == 201) "{}" else "{\"error\":\"invalid_session\"}", null)
            "/agent/suggestions" -> HttpResult(200, "{\"suggestions\":[]}", null)
            else -> HttpResult(404, "", null)
        }
    }
}

internal class RecordingListener : RavensightListener {
    val statuses = mutableListOf<String?>()
    val trackingDisabled = mutableListOf<String>()
    val feedbackFailed = mutableListOf<String>()
    var feedbackSubmitted = 0
    val suggestions = mutableListOf<List<Any?>>()

    override fun onStatusChanged(reason: String?) {
        statuses += reason
    }
    override fun onTrackingDisabled(source: String) {
        trackingDisabled += source
    }
    override fun onFeedbackFailed(reason: String) {
        feedbackFailed += reason
    }
    override fun onFeedbackSubmitted() {
        feedbackSubmitted++
    }
    override fun onSuggestionsReceived(suggestions: List<Any?>) {
        this.suggestions += suggestions
    }
}
