package com.realityse.ravensight

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.realityse.ravensight.core.ExitReasons
import com.realityse.ravensight.core.ExitRecord
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/**
 * Automatic error capture through the Android layer, on Robolectric with a
 * fake transport (no request leaves the test), a fake clock and a manual
 * worker. The crash hook is driven by hand: these are JVM tests, not a
 * device run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ErrorCaptureAndroidTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private val worker = ManualWorker { now }
    private val http = FakeTransport()
    private var exits: List<ExitRecord> = emptyList()

    /** The game's switch as the settings and session answers carry it; null leaves the key out. */
    private var answer: JSONObject? = JSONObject().put("enabled", true).put("trail", false)

    @Before
    fun setUp() {
        Ravensight.resetForTest()
        CrashHandler.uninstallForTest()
        app.getSharedPreferences(Ravensight.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        crashDir().deleteRecursively()
        http.override = { req ->
            when (req.path) {
                "/settings" -> HttpResult(200, JSONObject().put("trackingEnabled", true).apply { answer?.let { put("errorCapture", it) } }.toString(), null)
                "/session" -> HttpResult(201, JSONObject().put("token", "tok").put("expiresIn", 86400).apply { answer?.let { put("errorCapture", it) } }.toString(), null)
                else -> null
            }
        }
    }

    @After
    fun tearDown() {
        Ravensight.resetForTest()
        CrashHandler.uninstallForTest()
    }

    private fun crashDir() = File(app.noBackupFilesDir, "ravensight_crashes")

    private fun init(
        captureErrors: Boolean = true,
        key: String = "gt_live_test",
        consent: RavensightConsent = RavensightConsent.OPT_OUT,
    ) {
        Ravensight.initialize(
            app,
            RavensightConfig(ingestKey = key, apiUrl = "https://api.example.test", gameVersion = "1.0.0", optional = true, consent = consent, captureErrors = captureErrors),
            null,
            RavensightDeps(
                transport = http,
                clockMs = { now },
                worker = worker,
                mainPost = { it.run() },
                env = { null },
                observeProcessLifecycle = false,
                registerActivityCallbacks = false,
                random = { 0.5 },
                exitReasons = { exits },
            ),
        )
        worker.runDue()
    }

    private fun foreground() {
        Ravensight.controllerForTest!!.onForegroundSignal()
        worker.runDue()
    }

    /** A new process: the controller goes, the files and preferences stay. */
    private fun relaunch() {
        Ravensight.resetForTest()
        http.requests.clear()
        now += 60_000
    }

    /** What the crashing thread does, minus the process dying. */
    private fun crash(error: Throwable) {
        CrashHandler.sink?.invoke(Thread.currentThread(), error)
    }

    private fun errorEvents(): List<JSONObject> = http.requests
        .filter { it.path == "/track/batch" }
        .flatMap { r ->
            val arr = JSONObject(r.body!!).getJSONArray("events")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }
        .filter { it.getString("event") == "error" }

    @Test
    fun `the hook chains to the previous handler and never swallows`() {
        val seen = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, e -> seen += e }
        val hook = CrashHandler.Hook(previous)
        CrashHandler.sink = { _, _ -> throw IllegalStateException("capture path failure") }
        val boom = RuntimeException("boom")
        hook.uncaughtException(Thread.currentThread(), boom)
        assertEquals(listOf<Throwable>(boom), seen)
    }

    @Test
    fun `installed once, after the previous handler, only with a key and consent`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        init(key = "")
        assertFalse(CrashHandler.isInstalled)
        relaunch()
        init(consent = RavensightConsent.OPT_IN)
        assertFalse(CrashHandler.isInstalled)
        relaunch()
        init()
        assertTrue(CrashHandler.isInstalled)
        val hook = Thread.getDefaultUncaughtExceptionHandler() as CrashHandler.Hook
        assertSame(before, hook.previous)
        relaunch()
        init()
        assertSame(hook, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `a crash is written synchronously and sent on the next launch as one critical crash event`() {
        init()
        crash(IllegalStateException("save failed for bob@example.com"))
        assertEquals(1, crashDir().list()!!.size)
        relaunch()
        init()
        foreground()
        val events = errorEvents()
        assertEquals(1, events.size)
        val data = events[0].getJSONObject("data")
        assertEquals("error", data.getString("rs_kind"))
        assertEquals("crash", data.getString("rs_source"))
        assertEquals("critical", data.getString("rs_severity"))
        assertEquals("IllegalStateException", data.getString("rs_error_type"))
        assertEquals("save failed for [email]", data.getString("rs_message"))
        assertTrue(data.getString("rs_stack").contains("at "))
        assertFalse(data.has("rs_trail"))
        assertFalse(data.has("rs_fp"))
        assertEquals((now - 60_000) / 1000, events[0].getLong("timestamp"))
        assertEquals(0, crashDir().list()!!.size)
        // Gameplay first: game_started opens the batch, the error follows.
        val first = JSONObject(http.requests.first { it.path == "/track/batch" }.body!!).getJSONArray("events")
        assertEquals("game_started", first.getJSONObject(0).getString("event"))
    }

    @Test
    fun `boot captures are discarded unsent when the game's switch is off`() {
        init()
        crash(RuntimeException("x"))
        relaunch()
        answer = JSONObject().put("enabled", false)
        init()
        foreground()
        assertTrue(errorEvents().isEmpty())
        assertEquals(0, crashDir().list()!!.size)
        // And nothing new is recorded while it is off.
        crash(RuntimeException("y"))
        assertEquals(0, crashDir().list()!!.size)
    }

    @Test
    fun `an answer without errorCapture means on`() {
        answer = null
        init()
        crash(RuntimeException("x"))
        relaunch()
        init()
        foreground()
        assertEquals(1, errorEvents().size)
    }

    @Test
    fun `captureErrors false records nothing and deletes what an earlier build left`() {
        init()
        crash(RuntimeException("x"))
        relaunch()
        init(captureErrors = false)
        crash(RuntimeException("y"))
        foreground()
        assertTrue(errorEvents().isEmpty())
        assertEquals(0, crashDir().list()!!.size)
    }

    @Test
    fun `an opt-out deletes a stored crash and disarms the hook`() {
        init()
        crash(RuntimeException("x"))
        Ravensight.setTrackingEnabled(app, false)
        worker.runDue()
        assertEquals(0, crashDir().list()!!.size)
        crash(RuntimeException("y"))
        assertEquals(0, crashDir().list()!!.size)
    }

    @Test
    fun `the trail rides on a crash only when the game's trail switch is on`() {
        answer = JSONObject().put("enabled", true).put("trail", true)
        init()
        foreground()
        now += 1_500
        Ravensight.track("kickoff")
        Ravensight.log("warn", "stadium gt_live_secret9 missing")
        crash(RuntimeException("x"))
        relaunch()
        init()
        foreground()
        val trail = errorEvents().single().getJSONObject("data").getString("rs_trail")
        assertEquals("+1500 track kickoff\n+1500 warn stadium [token] missing", trail)

        relaunch()
        answer = JSONObject().put("enabled", true).put("trail", false)
        init()
        foreground()
        Ravensight.log("info", "not kept")
        crash(RuntimeException("y"))
        relaunch()
        init()
        foreground()
        assertFalse(errorEvents().single().getJSONObject("data").has("rs_trail"))
    }

    @Test
    fun `exit records report native crashes and ANRs once, after the first look`() {
        exits = listOf(ExitRecord(now - 5_000, ExitReasons.REASON_ANR, "old ANR"))
        init()
        foreground()
        assertTrue(errorEvents().isEmpty())

        relaunch()
        exits = exits + ExitRecord(now - 1_000, ExitReasons.REASON_CRASH_NATIVE, "signal 11") +
            ExitRecord(now - 500, ExitReasons.REASON_ANR, "Input dispatching timed out")
        init()
        foreground()
        val sent = errorEvents().map { it.getJSONObject("data") }
        assertEquals(listOf("REASON_CRASH_NATIVE", "REASON_ANR"), sent.map { it.getString("rs_error_type") })
        assertTrue(sent.all { it.getString("rs_source") == "crash" && it.getString("rs_severity") == "critical" && !it.has("rs_stack") })

        relaunch()
        init()
        foreground()
        assertTrue(errorEvents().isEmpty())
    }

    @Test
    fun `no key reads nothing and starts nothing`() {
        exits = listOf(ExitRecord(now, ExitReasons.REASON_ANR, "x"))
        init(key = "")
        assertFalse(CrashHandler.isInstalled)
        assertNull(CrashHandler.sink)
        assertFalse(Ravensight.controllerForTest!!.workerCreatedForTest)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `manual trackError is unchanged and unscrubbed on the device`() {
        answer = JSONObject().put("enabled", false)
        init()
        foreground()
        Ravensight.trackError("error", "player bob@example.com")
        Ravensight.flush()
        worker.runDue()
        val data = errorEvents().single().getJSONObject("data")
        assertEquals("player bob@example.com", data.getString("rs_message"))
        assertFalse(data.has("rs_source"))
    }

    @Test
    @Config(sdk = [30])
    fun `the platform exit records are read on API 30`() {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder().setTimestamp(now).setReason(ApplicationExitInfo.REASON_ANR).setDescription("anr").build(),
        )
        val read = SystemExitReasons.read(app)
        assertEquals(1, read.size)
        assertEquals(ExitReasons.REASON_ANR, read[0].reason)
        assertEquals("anr", read[0].description)
    }

    @Test
    @Config(sdk = [29])
    fun `below API 30 exit records are skipped`() {
        assertTrue(SystemExitReasons.read(app).isEmpty())
    }
}
