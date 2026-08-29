package com.realityse.ravensight

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A finished HTTP exchange. [status] is 0 when the network was unreachable. */
internal class HttpResult(
    val status: Int,
    val body: String,
    private val retryAfterHeader: String?,
) {
    /** Parsed Retry-After header in delta-seconds form, or null when absent. */
    val retryAfterSec: Long?
        get() = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    val json: JSONObject?
        get() = try {
            if (body.isEmpty()) null else JSONObject(body)
        } catch (e: JSONException) {
            null
        }

    /** The server's error code from {"error": "..."}, or [fallback]. */
    fun errorCode(fallback: String): String =
        json?.optString("error")?.takeIf { it.isNotEmpty() } ?: fallback
}

/**
 * Blocking JSON-over-HTTP on HttpURLConnection. No third party dependencies.
 * Only ever called from the SDK's single worker thread.
 */
internal class RavensightHttp(private val timeoutMs: Int) {

    /** Never throws: network failures come back as status 0. */
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            for ((name, value) in headers) connection.setRequestProperty(name, value)

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResult(status, text, connection.getHeaderField("Retry-After"))
        } catch (e: IOException) {
            HttpResult(0, "", null)
        } finally {
            connection?.disconnect()
        }
    }
}

/** Converts event data maps to org.json values. */
internal object RavensightJson {
    fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> value
        is Map<*, *> -> JSONObject().also { obj ->
            for ((k, v) in value) obj.put(k.toString(), toJsonValue(v))
        }
        is Iterable<*> -> JSONArray().also { arr ->
            for (v in value) arr.put(toJsonValue(v))
        }
        is Array<*> -> toJsonValue(value.asList())
        is Boolean, is Number, is String -> value
        else -> value.toString()
    }

    fun toJsonObject(map: Map<String, Any?>): JSONObject =
        toJsonValue(map) as JSONObject

    /** JSONArray to a plain list of maps, lists and primitives. */
    fun toPlain(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> buildMap {
            for (key in value.keys()) put(key, toPlain(value.get(key)))
        }
        is JSONArray -> buildList {
            for (i in 0 until value.length()) add(toPlain(value.get(i)))
        }
        else -> value
    }
}
