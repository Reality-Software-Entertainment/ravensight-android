package com.realityse.ravensight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** A captured report. Reuse this object to retry; its ID, time and image never change.
 * A report binds to the first session used for submission. It refuses a different
 * session instead of accidentally reserving screenshot usage a second time. */
class RavensightReport @JvmOverloads constructor(
    val message: String,
    val category: String = "other",
    screenshotData: ByteArray? = null,
    metadata: Map<String, Any?> = emptyMap(),
    val level: String = "",
    val expected: String = "",
    val steps: String = "",
    val frequency: String = "",
    val capturedAt: Long = System.currentTimeMillis() / 1000,
    val clientId: String = Ravensight.randomHex(16),
    private val unusableImage: Boolean = false,
) {
    private val image = screenshotData?.copyOf()
    private val context = metadata.toMap()
    internal fun imageBytes(): ByteArray? = image?.copyOf()
    private var boundSession: String? = null
    private var inFlight = false
    @Synchronized internal fun begin(session: String): String? {
        if (boundSession != null && boundSession != session) return "report_session_changed"
        if (inFlight) return "report_in_progress"
        boundSession = session; inFlight = true; return null
    }
    @Synchronized internal fun finish() { inFlight = false }
    internal fun refusal(now: Long): String? {
        if (message.isBlank()) return "empty_message"
        if (message.length > 500 || level.length > 100 || expected.length > 500 || steps.length > 1000 || frequency.length > 80 || !clientId.matches(Regex("^[a-zA-Z0-9_-]{8,80}$"))) return "invalid_report"
        if (category !in RavensightConfig.FEEDBACK_CATEGORIES) return "invalid_category"
        if (capturedAt < now - 86400 || capturedAt > now + 60) return "invalid_capture_time"
        if (context.any { (key, value) -> !key.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]{0,39}$")) || value !is String && value !is Boolean && value !is Byte && value !is Short && value !is Int && value !is Long && value !is Float && value !is Double || value.toString().length > 500 || value is Number && !value.toDouble().isFinite() }) return "invalid_metadata"
        if (RavensightJson.toJsonObject(context).toString().toByteArray(Charsets.UTF_8).size > 8000) return "invalid_metadata"
        if (unusableImage) return "invalid_image"
        if (image != null) {
            if (image.isEmpty()) return "invalid_image"
            if (image.size > MAX_IMAGE_BYTES) return "image_too_large"
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, options)
            if (options.outMimeType !in listOf("image/jpeg", "image/png", "image/webp") || options.outWidth <= 0 || options.outHeight <= 0) return "invalid_image"
            if (options.outWidth.toLong() * options.outHeight > 4000000) return "image_too_large"
        }
        return null
    }
    internal fun body(): JSONObject = JSONObject().put("clientId", clientId).put("capturedAt", capturedAt)
        .put("message", message).put("category", category).put("context", RavensightJson.toJsonObject(context))
        .put("level", level).put("expected", expected).put("steps", steps).put("frequency", frequency)
        .put("image", image != null).also { if (image != null) it.put("bytes", image.size).put("sha256", MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }) }
    companion object {
        const val MAX_IMAGE_BYTES = 1000000
        /** Pass only a player-approved capture with private UI hidden. Never captures automatically. */
        @JvmStatic @JvmOverloads fun fromBitmap(message: String, screenshot: Bitmap, category: String = "other", metadata: Map<String, Any?> = emptyMap()): RavensightReport {
            val bytes = try {
                var encoded: ByteArray? = null
                for ((side, quality) in listOf(1600 to 78, 1600 to 60, 1200 to 60, 800 to 60)) {
                    val scale = minOf(1.0, side.toDouble() / maxOf(screenshot.width, screenshot.height))
                    val bitmap = Bitmap.createScaledBitmap(screenshot, maxOf(1, (screenshot.width * scale).toInt()), maxOf(1, (screenshot.height * scale).toInt()), true)
                    try { val output = ByteArrayOutputStream(); if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= MAX_IMAGE_BYTES) encoded = output.toByteArray() }
                    finally { if (bitmap !== screenshot) bitmap.recycle() }
                    if (encoded != null) break
                }
                encoded
            } catch (_: Exception) { null }
            return RavensightReport(message, category, bytes, metadata, unusableImage = bytes == null)
        }
    }
}

data class RavensightReportReceipt(val id: String, val reportId: String?, val status: String, val usageEvents: Int, val clientId: String)
data class RavensightReportResult(val receipt: RavensightReportReceipt? = null, val error: String? = null, val httpStatus: Int = 0)

/** Storage has a separate byte transport: account/session/playtest headers never go to it. */
internal fun interface ReportUpload { fun upload(url: String, fields: Map<String, String>, bytes: ByteArray): HttpResult }
internal class ReportHttpUpload(private val timeout: Int) : ReportUpload {
    override fun upload(url: String, fields: Map<String, String>, bytes: ByteArray): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            if (!url.startsWith("https://")) return HttpResult(0, "", null)
            val boundary = "ravensight" + Ravensight.randomHex(16)
            val body = ByteArrayOutputStream()
            fun append(text: String) { body.write(text.toByteArray(Charsets.UTF_8)) }
            for ((key, value) in fields) {
                if (key.any { it == '\r' || it == '\n' || it == '"' }) return HttpResult(0, "", null)
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"screenshot\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            body.write(bytes); append("\r\n--$boundary--\r\n")
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"; connection.connectTimeout = timeout; connection.readTimeout = timeout
            connection.instanceFollowRedirects = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(body.size())
            connection.outputStream.use { body.writeTo(it) }
            HttpResult(connection.responseCode, "", null)
        } catch (_: Exception) { HttpResult(0, "", null) } finally { connection?.disconnect() }
    }
}

internal class ReportSubmission(
    private val report: RavensightReport, private val apiUrl: String, private val headers: Map<String, String>,
    private val transport: RavensightTransport, private val upload: ReportUpload,
    private val allowed: () -> Boolean, private val delay: (Runnable) -> Unit,
    private val completion: (RavensightReportResult) -> Unit,
) {
    private var finished = false
    private fun finish(result: RavensightReportResult) { if (!finished) { finished = true; report.finish(); completion(result) } }
    private fun gate(): Boolean { if (finished) return false; if (!allowed()) { finish(RavensightReportResult(error = "report_context_changed")); return false }; return true }
    private fun request(method: String, suffix: String, body: JSONObject? = null, accept: Set<Int>): JSONObject? {
        if (!gate()) return null
        val response = try { transport.request(method, "$apiUrl/reports$suffix", headers, body?.toString()) } catch (_: Exception) { HttpResult(0, "", null) }
        if (!gate()) return null
        val json = response.json
        if (response.status !in accept || json == null) { finish(RavensightReportResult(error = response.errorCode("report_failed"), httpStatus = response.status)); return null }
        return json
    }
    fun start() { val state = request("POST", "", report.body(), setOf(201)) ?: return; val id = state.optString("id"); if (!id.matches(Regex("^[a-zA-Z0-9_-]{1,128}$"))) { finish(RavensightReportResult(error = "invalid_response")); return }; advance(id, state, false, 0) }
    private fun advance(id: String, state: JSONObject, uploaded: Boolean, polls: Int) {
        if (!gate()) return
        when (state.optString("status")) {
            "complete" -> finish(RavensightReportResult(receipt = RavensightReportReceipt(id, state.optString("reportId").takeIf { it.isNotEmpty() }, "complete", state.optInt("usageEvents", 1), report.clientId)))
            "canceled" -> finish(RavensightReportResult(error = "report_canceled"))
            "pending" -> if (!uploaded) {
                val bytes = report.imageBytes() ?: run { finish(RavensightReportResult(error = "submission_conflict")); return }
                val form = request("POST", "/$id/upload", JSONObject(), setOf(200)) ?: return
                val url = form.optString("url"); val fields = form.optJSONObject("fields")
                if (!url.startsWith("https://") || fields == null || fields.length() > 50 || form.toString().toByteArray().size > 64000) { finish(RavensightReportResult(error = "invalid_upload")); return }
                if (!gate()) return
                val response = try { upload.upload(url, fields.keys().asSequence().associateWith { fields.getString(it) }, bytes) } catch (_: Exception) { HttpResult(0, "", null) }
                if (!gate()) return
                if (response.status !in 200..299) { finish(RavensightReportResult(error = "upload_failed", httpStatus = response.status)); return }
                val finalized = request("POST", "/$id/finalize", JSONObject(), setOf(200, 202)) ?: return
                advance(id, finalized, true, 0)
            } else poll(id, polls)
            "processing" -> poll(id, polls)
            else -> finish(RavensightReportResult(error = "invalid_response"))
        }
    }
    private fun poll(id: String, polls: Int) {
        if (polls >= 8) { finish(RavensightReportResult(error = "report_processing")); return }
        delay(Runnable { val state = request("GET", "/$id", accept = setOf(200)) ?: return@Runnable; advance(id, state, true, polls + 1) })
    }
}
