package com.realityse.ravensight

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RavensightReportsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = System.currentTimeMillis()
    private val worker = ManualWorker { now }
    private val http = FakeTransport()
    private var uploadStatus = 204
    private var uploaded: ByteArray? = null
    private var onUpload: (() -> Unit)? = null
    private val results = mutableListOf<RavensightReportResult>()
    @Before fun before() { Ravensight.resetForTest(); Ravensight.prefs(app).edit().clear().commit() }
    @After fun after() { Ravensight.resetForTest() }
    private fun initialize(enabled: Boolean = true, key: String = "gt_live_test") {
        http.override = { request -> when {
            request.path == "/reports" -> HttpResult(201, if (JSONObject(request.body!!).getBoolean("image")) "{\"id\":\"report_123\",\"status\":\"pending\"}" else "{\"id\":\"report_123\",\"status\":\"complete\",\"usageEvents\":1}", null)
            request.path.endsWith("/upload") -> HttpResult(200, "{\"url\":\"https://storage.test/upload\",\"fields\":{\"key\":\"capture\",\"policy\":\"signed\"}}", null)
            request.path.endsWith("/finalize") -> HttpResult(202, "{\"status\":\"processing\"}", null)
            request.path == "/reports/report_123" -> HttpResult(200, "{\"status\":\"complete\",\"reportId\":\"stored\",\"usageEvents\":20001}", null)
            else -> null
        } }
        Ravensight.initialize(app, RavensightConfig(ingestKey = key, apiUrl = "https://api.example.test", enabled = enabled), null,
            RavensightDeps(transport = http, worker = worker, clockMs = { now }, mainPost = { it.run() }, observeProcessLifecycle = false, registerActivityCallbacks = false,
                reportUpload = ReportUpload { url, fields, bytes -> assertEquals("https://storage.test/upload", url); assertEquals("signed", fields["policy"]); uploaded = bytes.copyOf(); onUpload?.invoke(); HttpResult(uploadStatus, "", null) }))
        worker.runDue(); Ravensight.controllerForTest!!.onForegroundSignal(); worker.runDue()
    }
    private fun png(): ByteArray { val bytes = ByteArrayOutputStream(); Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, bytes); return bytes.toByteArray() }
    private fun submit(report: RavensightReport) { Ravensight.submitReport(report) { results += it }; worker.runDue() }
    private fun poll() { now += 500; worker.runDue() }
    @Test fun textAndImageFollowSeparateContractWithStableSnapshot() {
        initialize(); submit(RavensightReport("Text only")); assertEquals(1, results.single().receipt?.usageEvents)
        val bytes = png(); val original = bytes.copyOf(); val metadata = mutableMapOf<String, Any?>("zone" to "forest")
        val report = RavensightReport("Image", screenshotData = bytes, metadata = metadata)
        bytes.fill(0); metadata["zone"] = "changed"
        submit(report); assertEquals(1, results.size); assertArrayEquals(original, uploaded); poll()
        assertEquals(20001, results.last().receipt?.usageEvents)
        val request = http.requests.last { it.path == "/reports" }; val body = JSONObject(request.body!!)
        assertEquals(report.clientId, body.getString("clientId")); assertEquals(report.capturedAt, body.getLong("capturedAt")); assertEquals("forest", body.getJSONObject("context").getString("zone"))
        assertEquals(64, body.getString("sha256").length); assertNotNull(request.headers["X-Session-Token"]); assertFalse(request.headers.containsKey("X-API-Key"))
    }
    @Test fun uploadFailureRetriesSameReportAndNeverFallsBackToText() {
        initialize(); uploadStatus = 500; val report = RavensightReport("Retry", screenshotData = png())
        submit(report); assertEquals("upload_failed", results.single().error); assertFalse(http.paths().any { it.endsWith("/finalize") || it == "/feedback" })
        val original = http.requests.last { it.path == "/reports" }
        uploadStatus = 204; submit(report); poll()
        val retry = http.requests.last { it.path == "/reports" }; assertEquals(original.body, retry.body); assertEquals(original.headers, retry.headers); assertNotNull(results.last().receipt)
    }
    @Test fun expiredSessionNeverAutoRetriesOrOpensAnotherSession() {
        initialize(); val original = http.override; http.override = { if (it.path == "/reports") HttpResult(401, "{\"error\":\"invalid_session\"}", null) else original?.invoke(it) }
        val count = http.paths().count { it == "/session" }; submit(RavensightReport("Expired")); assertEquals("invalid_session", results.single().error)
        assertEquals(count, http.paths().count { it == "/session" }); assertEquals(1, http.paths().count { it == "/reports" })
    }
    @Test fun consentAndIdentityChangesDuringUploadStopFinalization() {
        for (reset in listOf(false, true)) {
            Ravensight.resetForTest(); results.clear(); initialize()
            onUpload = { if (reset) Ravensight.resetDeviceId() else Ravensight.setTrackingEnabled(app, false) }
            val before = http.paths().count { it.endsWith("/finalize") }
            submit(RavensightReport("Private", screenshotData = png()))
            assertEquals("report_context_changed", results.single().error); assertEquals(before, http.paths().count { it.endsWith("/finalize") })
            Ravensight.prefs(app).edit().clear().commit()
        }
    }
    @Test fun rejectsBadImageAndContextWithoutSendingText() {
        initialize(); val baseline = http.requests.size
        for (report in listOf(RavensightReport("bad", screenshotData = byteArrayOf(1, 2)), RavensightReport("large", screenshotData = ByteArray(1000001)), RavensightReport("bad", metadata = mapOf("value" to Double.NaN)), RavensightReport("bad", metadata = mapOf("value" to listOf("nested"))))) submit(report)
        assertEquals(listOf("invalid_image", "image_too_large", "invalid_metadata", "invalid_metadata"), results.map { it.error }); assertEquals(baseline, http.requests.size)
    }
    @Test fun mutableNumericMetadataIsRefusedAndMissingSessionDoesNotCreateOne() {
        initialize(); val before = http.requests.size
        submit(RavensightReport("Mutable", metadata = mapOf("count" to java.util.concurrent.atomic.AtomicInteger(1))))
        assertEquals("invalid_metadata", results.single().error); assertEquals(before, http.requests.size)
        now += 86400000
        val sessions = http.paths().count { it == "/session" }
        submit(RavensightReport("No session", capturedAt = now / 1000))
        assertEquals("no_session", results.last().error)
        assertEquals(sessions, http.paths().count { it == "/session" })
    }
    @Test fun disabledAndInertReturnCallbackWithoutRequests() {
        initialize(key = ""); submit(RavensightReport("No")); assertEquals("disabled", results.single().error); assertTrue(http.requests.isEmpty())
    }
    @Test fun processingIsBoundedAndDuplicateConcurrentSubmissionIsRefused() {
        initialize(); val original = http.override; http.override = { if (it.path == "/reports/report_123") HttpResult(200, "{\"status\":\"processing\"}", null) else original?.invoke(it) }
        val report = RavensightReport("Wait", screenshotData = png()); submit(report); submit(report)
        assertEquals("report_in_progress", results.single().error)
        repeat(9) { poll() }; assertEquals("report_processing", results.last().error); assertEquals(8, http.paths().count { it == "/reports/report_123" })
    }
    @Test fun reportCannotMoveToAnotherSession() {
        val report = RavensightReport("Stable"); assertNull(report.begin("first")); report.finish(); assertEquals("report_session_changed", report.begin("second")); assertNull(report.begin("first")); report.finish()
    }
    @Test fun bitmapHelperCapturesOnceAndRefusesUnusableCapture() {
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        val report = RavensightReport.fromBitmap("Captured", bitmap)
        // The helper stamps the wall clock, so read it right after the report
        // is built: the test's own `now`, taken when the test instance was
        // made, can be more than the 60 second allowance behind on a slow
        // Robolectric start.
        val capturedNow = System.currentTimeMillis() / 1000
        assertNull(report.refusal(capturedNow)); assertTrue(report.imageBytes()!!.size <= RavensightReport.MAX_IMAGE_BYTES)
        bitmap.recycle(); val invalid = RavensightReport.fromBitmap("Recycled", bitmap)
        assertEquals("invalid_image", invalid.refusal(System.currentTimeMillis() / 1000))
    }
}
