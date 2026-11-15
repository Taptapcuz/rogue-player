package com.mytv.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Disk-backed EPG cache, the way TiViMate does it: every guide that comes back
 * from the provider is written under `files/epg/` keyed by (playlist, stream),
 * so the grid still has programmes after a restart, and a provider that resets
 * its sockets mid-browse can't leave the guide blank.
 *
 * Entries are small (a day of listings per channel); [prune] drops anything
 * older than a week so the folder can't grow without bound.
 */
class EpgCache(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "epg")

    /** A cached guide plus the moment it was fetched. */
    class Entry(val programs: List<EpgProgram>, val fetchedAt: Long)

    init {
        runCatching { dir.mkdirs() }
    }

    /** Cached guide for [key], or null when nothing usable is on disk. */
    fun read(key: String): Entry? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val fetchedAt = root.optLong("fetchedAt", 0L)
            val arr = root.optJSONArray("programs") ?: JSONArray()
            val programs = ArrayList<EpgProgram>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = EpgProgram(
                    start = o.optLong("s"),
                    end = o.optLong("e"),
                    title = o.optString("t"),
                    description = o.optString("d").takeIf { it.isNotBlank() },
                    icon = o.optString("i").takeIf { it.isNotBlank() },
                    archive = o.optBoolean("a"),
                )
                if (p.isValid()) programs.add(p)
            }
            if (programs.isEmpty()) null else Entry(programs, fetchedAt)
        }.onFailure { Log.w(TAG, "cache read failed for $key", it) }.getOrNull()
    }

    /** Persist a freshly fetched guide. Only non-empty sets are worth storing. */
    fun write(key: String, programs: List<EpgProgram>, fetchedAt: Long = System.currentTimeMillis()) {
        if (programs.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val arr = JSONArray()
            programs.forEach { p ->
                arr.put(JSONObject().apply {
                    put("s", p.start)
                    put("e", p.end)
                    put("t", p.title)
                    p.description?.let { put("d", it) }
                    p.icon?.let { put("i", it) }
                    if (p.archive) put("a", true)
                })
            }
            val root = JSONObject().apply {
                put("fetchedAt", fetchedAt)
                put("programs", arr)
            }
            // Write to a temp file then rename, so a kill mid-write can't leave a
            // half-parsed entry that would blank that channel forever.
            val target = fileFor(key)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }.onFailure { Log.w(TAG, "cache write failed for $key", it) }
    }

    /** Delete entries older than [maxAgeMs] (default 7 days). */
    fun prune(maxAgeMs: Long = 7L * 24 * 3600_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    /** Throw the whole cache away (playlist switch / explicit refresh). */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(key: String): File =
        File(dir, sha1(key) + ".json")

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { b -> sb.append("%02x".format(b)) }
        return sb.toString()
    }

    private companion object {
        const val TAG = "EpgCache"
    }
}
