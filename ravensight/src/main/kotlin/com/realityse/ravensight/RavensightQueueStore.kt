package com.realityse.ravensight

import com.realityse.ravensight.core.RavensightCoreConfig
import com.realityse.ravensight.core.RavensightEvent
import com.realityse.ravensight.core.RavensightWire
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The persisted queue (RavensightConfig.persistQueue, on by default): one
 * JSON file per ingest key in the app's private files directory
 * (`ravensight/queue-<16 hex>.json`, 64-bit FNV-1a of the key), queue
 * format version 2 (docs/sdk/DELIVERY_CONTRACT.md section 2). Each group of
 * events carries the origin of the session it was tracked under, exactly as
 * the /session answer gave it. Session tokens, signing secrets and an
 * error's `rs_trail` are never written. Written atomically (a temporary file renamed over the old one);
 * an empty queue deletes the file. Worker thread only.
 */
internal class RavensightQueueStore(val file: File) {

    /** The one temporary file an atomic write goes through, beside [file]. */
    val tempFile: File get() = File(file.parentFile, file.name + ".tmp")

    class Loaded(val events: List<RavensightEvent>, val dropped: Int)

    /**
     * Reads the file. A missing, unreadable, malformed or unknown version
     * file is an empty queue. Events older than 89 days, and the oldest past
     * [cap], are dropped and counted.
     */
    fun load(nowMs: Long, cap: Int): Loaded {
        // A write a crash cut short leaves its temporary file behind.
        tempFile.delete()
        val root = try {
            if (!file.exists()) return Loaded(emptyList(), 0)
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return Loaded(emptyList(), 0)
        }
        val version = root.optInt("version", 0)
        if (version != 1 && version != 2) return Loaded(emptyList(), 0)
        // Version 1 (other SDKs' earlier files) stored seconds.
        val scale = if (version == 1) 1000L else 1L
        val groups = root.optJSONArray("groups") ?: return Loaded(emptyList(), 0)
        val events = ArrayList<RavensightEvent>()
        var dropped = 0
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            @Suppress("UNCHECKED_CAST")
            val origin = if (version == 2) {
                group.optJSONObject("origin")?.let { RavensightJson.toPlain(it) as Map<String, Any?> }
            } else {
                null
            }
            val list = group.optJSONArray("events") ?: continue
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val name = item.optString("event", "")
                if (name.isEmpty() || !item.has("timestamp")) continue
                val timestamp = item.optLong("timestamp") * scale
                if (nowMs - timestamp > RavensightCoreConfig.MAX_QUEUED_AGE_MS) {
                    dropped++
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val data = (item.optJSONObject("data")?.let { RavensightJson.toPlain(it) } ?: emptyMap<String, Any?>()) as Map<String, Any?>
                events += RavensightEvent(
                    name, data, timestamp,
                    origin = origin,
                    auto = item.optBoolean("automaticError", false),
                    restored = true,
                )
            }
        }
        val limit = cap.coerceAtLeast(1)
        if (events.size > limit) {
            dropped += events.size - limit
            return Loaded(events.subList(events.size - limit, events.size).toList(), dropped)
        }
        return Loaded(events, dropped)
    }

    /** Writes the events (consecutive ones with the same origin form a group). */
    fun save(events: List<RavensightEvent>): Boolean = try {
        if (events.isEmpty()) {
            delete()
        } else {
            val groups = JSONArray()
            var current: JSONObject? = null
            var currentSession: String? = null
            for (event in events) {
                if (current == null || event.originSession != currentSession) {
                    val group = JSONObject()
                    event.origin?.let { group.put("origin", RavensightJson.toJsonValue(it)) }
                    group.put("events", JSONArray())
                    currentSession = event.originSession
                    groups.put(group)
                    current = group
                }
                val item = JSONObject()
                    .put("event", event.name)
                    .put("timestamp", event.timestampMs)
                    // A trail never reaches the queue file (the queue rules,
                    // rule 3): the event keeps it in memory, and one restored
                    // from disk goes without it.
                    .put("data", RavensightJson.toJsonValue(event.data - "rs_trail"))
                if (event.auto) item.put("automaticError", true)
                current!!.getJSONArray("events").put(item)
            }
            val text = JSONObject().put("version", 2).put("groups", groups).toString()
            file.parentFile?.mkdirs()
            val temp = tempFile
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.delete()
                false
            } else {
                true
            }
        }
    } catch (_: Exception) {
        tempFile.delete()
        false
    }

    /** Deletes the queue file and any temporary file beside it. */
    fun delete(): Boolean {
        file.delete()
        tempFile.delete()
        return !file.exists() && !tempFile.exists()
    }

    companion object {
        fun forKey(filesDir: File, ingestKey: String): RavensightQueueStore =
            RavensightQueueStore(File(File(filesDir, "ravensight"), RavensightWire.queueFileName(ingestKey.trim())))
    }
}
