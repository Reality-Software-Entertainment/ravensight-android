package com.realityse.ravensight

import org.json.JSONArray
import org.json.JSONObject

/** Public, bounded config data. Never contains server credentials. */
internal data class RemoteConfig(val values: Map<String, Any?>, val experiments: Map<String, String>, val etag: String, val raw: String) {
    companion object {
        private val key = Regex("^[a-z][a-z0-9_]{0,63}$")
        private val variant = Regex("^[a-z][a-z0-9_-]{0,39}$")
        private fun safe(value: Any?, depth: Int = 0): Boolean {
            if (depth > 20) return false
            return when (value) {
                is JSONObject -> value.keys().asSequence().all { it !in setOf("__proto__", "prototype", "constructor") && safe(value.get(it), depth + 1) }
                is JSONArray -> (0 until value.length()).all { safe(value.get(it), depth + 1) }
                is Number -> value.toDouble().isFinite()
                null, JSONObject.NULL -> true
                is String, is Boolean -> true
                else -> false
            }
        }
        fun parse(raw: String): RemoteConfig? = try {
            if (raw.toByteArray(Charsets.UTF_8).size > 430000) null else {
                val doc = JSONObject(raw)
                val values = doc.getJSONObject("values")
                val rows = doc.getJSONArray("experiments")
                val etag = doc.getString("etag")
                require(values.length() <= 50 && rows.length() <= 10 && Regex("^\"[a-f0-9]{64}\"$").matches(etag))
                for (k in values.keys()) {
                    require(key.matches(k) && safe(values.get(k)))
                    require(JSONArray().put(values.get(k)).toString().toByteArray(Charsets.UTF_8).size <= 8194)
                }
                val experiments = linkedMapOf<String, String>()
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val k = row.get("key") as String
                    val v = row.get("variant") as String
                    require(key.matches(k) && variant.matches(v) && !experiments.containsKey(k))
                    experiments[k] = v
                }
                @Suppress("UNCHECKED_CAST")
                RemoteConfig(RavensightJson.toPlain(values) as Map<String, Any?>, experiments, etag, raw)
            }
        } catch (_: Exception) { null }
    }
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, fallback: T): T {
        val value = values[key] ?: return fallback
        val result: Any? = when (fallback) {
            is Boolean -> value as? Boolean
            is String -> value as? String
            is Int -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
            is Long -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && kotlin.math.abs(it) <= 9007199254740991.0 }?.toLong()
            is Double -> (value as? Number)?.toDouble()
            is Float -> (value as? Number)?.toFloat()?.takeIf { it.isFinite() }
            is Map<*, *> -> if (value is Map<*, *>) RavensightJson.toPlain(RavensightJson.toJsonValue(value)) else null
            is List<*> -> if (value is List<*>) RavensightJson.toPlain(RavensightJson.toJsonValue(value)) else null
            else -> null
        }
        return result as? T ?: fallback
    }
}
