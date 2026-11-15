package com.mytv.app.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Fetches and parses the Xtream Codes EPG for one channel.
 *
 * Per the open player_api spec, try `get_simple_data_table?stream_id=X` first;
 * if it yields no programmes (or errors), fall back to `get_short_epg?stream_id=X`.
 * Titles/descriptions are decoded from base64 when they arrive encoded, and
 * times are parsed tolerantly (epoch seconds / millis / ISO date strings).
 */
object EpgParser {

    private const val TAG = "EpgParser"
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder()
                HttpAgent.HEADERS.forEach { (k, v) -> b.header(k, v) }
                chain.proceed(b.build())
            }
            .build()
    }

    private val dateFormats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )

    /**
     * Fetch EPG programmes for one live [streamId]. [apiUrl] is the Xtream base
     * URL already carrying credentials, e.g.
     * `http://host/player_api.php?username=..&password=..`.
     */
    suspend fun load(apiUrl: String, streamId: String): List<EpgProgram> = withContext(Dispatchers.IO) {
        runCatching {
            val params = "&stream_id=$streamId"

            val simple = fetch(apiUrl, "get_simple_data_table", params)
            val fromSimple = simple?.let { parseEpg(it) }.orEmpty()
            if (fromSimple.isNotEmpty()) return@withContext fromSimple

            // Empty or failed → short epg
            val short = fetch(apiUrl, "get_short_epg", params) ?: return@withContext emptyList()
            parseEpg(short)
        }.getOrElse { t ->
            Log.w(TAG, "epg load failed", t)
            emptyList()
        }
    }

    private fun fetch(apiUrl: String, action: String, params: String): String? {
        val base = apiUrl.substringBefore("player_api.php") + "player_api.php"
        val auth = apiUrl.substringAfter('?', "").takeIf { it.isNotEmpty() }?.let { "$it&" } ?: ""
        val url = "$base?$auth" + "action=$action$params"
        val req = Request.Builder().url(url).header("User-Agent", HttpAgent.UA).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "epg fetch failed ($action)", t)
            null
        }
    }

    /** Parse `[{...}]` or `{"epg_listings":[{...}]}` (also `listings`) shapes. */
    fun parseEpg(json: String): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        try {
            val arr: JSONArray = when {
                json.trimStart().startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("epg_listings") ?: obj.optJSONArray("listings") ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parseProgram(o)?.takeIf { it.isValid() }?.let { programs.add(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parseEpg failed", t)
        }
        return programs
    }

    private fun parseProgram(o: JSONObject): EpgProgram? {
        val title = decodeText(o.optString("title"))
        val desc = decodeText(o.optString("description").ifBlank { o.optString("desc") })
            .takeIf { it.isNotBlank() }
        val icon = o.optString("icon").ifBlank { o.optString("cover") }.takeIf { it.isNotBlank() }
        val archive = o.optBoolean("has_archive") || o.optBoolean("archive")

        val start = parseTime(o.optString("start_timestamp").ifBlank { o.optString("start") })
        val end = parseTime(
            o.optString("stop_timestamp")
                .ifBlank { o.optString("end_timestamp") }
                .ifBlank { o.optString("stop") }
        )

        if (start <= 0L || end <= start) return null
        return EpgProgram(start, end, title.ifBlank { "EPG" }, desc, icon, archive)
    }

    /** Tolerant time parse: epoch seconds / epoch millis / common date strings. Returns 0 if unknown. */
    fun parseTime(value: String): Long {
        val v = value.trim()
        if (v.isEmpty()) return 0L

        v.toDoubleOrNull()?.let { d ->
            if (d > 100_000_000_000.0) return d.toLong()          // already millis
            if (d > 0) return (d * 1000.0).toLong()               // seconds → millis
        }

        for (pattern in dateFormats) {
            try {
                val df = SimpleDateFormat(pattern, Locale.US)
                df.timeZone = TimeZone.getTimeZone("UTC")
                val date = df.parse(v) ?: continue
                return date.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    /** Decode base64 values when they decode to readable UTF-8; else return raw. */
    fun decodeText(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return v
        return try {
            val decoded = String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.isNotEmpty() && isReadable(decoded)) decoded else v
        } catch (_: Exception) {
            v
        }
    }

    private fun isReadable(s: String): Boolean {
        for (c in s) {
            if (c == '\uFFFD') return false
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) return false
        }
        return true
    }
}
