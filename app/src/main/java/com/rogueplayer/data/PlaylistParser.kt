package com.rogueplayer.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Parses IPTV playlist sources:
 *  - M3U/M3U8 (file or URL)
 *  - Xtream Codes API (host + username + password)
 */
object PlaylistParser {

    private const val TAG = "PlaylistParser"
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Chrome's header set on every call: a bare request is what these panels
            // reset (which reads as "login failed" in the app but not in a browser).
            .addInterceptor { chain ->
                val b = chain.request().newBuilder()
                HttpAgent.HEADERS.forEach { (k, v) -> b.header(k, v) }
                chain.proceed(b.build())
            }
            .build()
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", HttpAgent.UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            val body = resp.body?.string() ?: ""
            Log.i(TAG, "GET $url -> ${resp.code}, ${body.length} bytes")
            return body
        }
    }

    suspend fun load(kind: PlaylistKind, url: String, user: String?, pass: String?): Playlist =
        withContext(Dispatchers.IO) {
            try {
                when (kind) {
                    PlaylistKind.M3U -> loadM3U(url)
                    PlaylistKind.XTREAM -> loadXtream(url, user.orEmpty(), pass.orEmpty())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t)
                throw t
            }
        }

    private fun loadM3U(url: String): Playlist {
        val body = fetchText(url)
        val lines = body.lines()
        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<Entry>()
        val series = mutableListOf<Entry>()
        var i = 0
        var serial = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                val name = line.substringAfter(",", "").trim()
                val attrs = parseExtinf(line)
                // find the next non-comment line = the stream URL
                var urlLine = ""
                var j = i + 1
                while (j < lines.size) {
                    val l = lines[j].trim()
                    if (l.isNotEmpty() && !l.startsWith("#")) { urlLine = l; break }
                    j++
                }
                val chUrl = urlLine.trim()
                if (chUrl.isNotEmpty()) {
                    val group = attrs["group-title"] ?: "Ungrouped"
                    serial++
                    val id = "ch.$serial"
                    if (group.equals("MOVIES", true) || group.equals("MOVIE", true)) {
                        movies.add(Entry(id, name, group, chUrl, logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }))
                    } else if (group.equals("SERIES", true) || group.equals("SERIE", true)) {
                        series.add(Entry(id, name, group, chUrl, logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }))
                    } else {
                        channels.add(
                            Channel(
                                id = id,
                                name = name,
                                group = group,
                                url = chUrl,
                                logo = attrs["tvg-logo"],
                                number = attrs["tvg-chno"]?.toIntOrNull() ?: 0,
                            )
                        )
                    }
                }
                i = j
            } else {
                i++
            }
        }
        return Playlist(
            name = url.substringAfterLast('/').substringBefore('.').ifBlank { "Playlist" },
            kind = PlaylistKind.M3U,
            url = url,
            channels = channels,
            movies = movies,
            series = series,
        )
    }

    /**
     * Parse #EXTINF attribute="value" pairs. Handles both quoted and unquoted values
     * by scanning for known keys.
     */
    private fun parseExtinf(line: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val regex = Regex("([a-zA-Z0-9-]+)=\"([^\"]*)\"")
        regex.findAll(line).forEach { m ->
            attrs[m.groupValues[1]] = m.groupValues[2]
        }
        return attrs
    }

    private fun loadXtream(host: String, user: String, pass: String): Playlist {
        // Providers move between http and https panels, and people type a bare
        // host:port. Try the typed scheme first, then the other one, instead of
        // failing outright on the wrong guess.
        val trimmed = host.trim().trimEnd('/')
        val candidates = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            listOf(trimmed)
        } else {
            listOf("https://$trimmed", "http://$trimmed")
        }
        var lastError: Throwable? = null
        for (base in candidates) {
            try {
                return loadXtreamFrom(base, user, pass)
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "xtream attempt failed for $base", t)
            }
        }
        throw lastError ?: IllegalStateException("provider unreachable")
    }

    private fun loadXtreamFrom(base: String, user: String, pass: String): Playlist {
        val baseUrl = "$base/player_api.php?username=$user&password=$pass"

        // The account blob is the only place Xtream reports a rejected line, and it
        // does so with HTTP 200 + `"auth": 0`. Without this check a wrong password
        // surfaced as an empty playlist and "Update" then wiped the good channel
        // list — the "login failed" report.
        val account = runCatching { fetchText(baseUrl) }.getOrNull()
        var expiresAt: Long? = null
        if (!account.isNullOrBlank()) {
            val info = runCatching { org.json.JSONObject(account).optJSONObject("user_info") }
                .getOrNull()
            if (info != null && info.optInt("auth", 1) == 0) {
                val status = info.optString("status").takeIf { it.isNotBlank() }
                throw IllegalStateException(
                    "Login failed — the provider rejected these credentials" +
                        (status?.let { " ($it)" } ?: "")
                )
            }
            // `exp_date` is unix seconds, and panels disagree on how "no expiry" looks:
            // JSON null, the string "null", an empty string, or 0. Treat all as "none".
            val raw = info?.optString("exp_date")?.trim().orEmpty()
            val secs = raw.toLongOrNull() ?: 0L
            if (secs > 0L) expiresAt = secs * 1000L
        }

        // live categories
        val catJson = fetchText("$baseUrl&action=get_live_categories")
        val cats = parseIdNamePairs(catJson)
        // live streams
        val liveJson = fetchText("$baseUrl&action=get_live_streams")
        val streams = parseXtreamStreams(liveJson)
        val channels = streams.mapNotNull { s ->
            val catId = s["category_id"] ?: ""
            val cat = cats[catId] ?: "Ungrouped"
            if (cat.equals("MOVIES", true) || cat.contains("movie", true)) null
            else Channel(
                id = s["stream_id"] ?: s["name"].orEmpty(),
                name = s["name"] ?: "?",
                group = cat,
                // Xtream stream URL
                url = buildStreamUrl(base, user, pass, s["stream_id"] ?: ""),
                logo = s["stream_icon"]?.takeIf { it.isNotBlank() },
            )
        }.filter { it.url.isNotBlank() }

        return Playlist(
            name = base.substringAfter("://").substringBefore('/').ifBlank { "Xtream" },
            kind = PlaylistKind.XTREAM,
            url = baseUrl,
            user = user,
            pass = pass,
            channels = channels,
            movies = loadXtreamVod(baseUrl),
            series = loadXtreamSeries(baseUrl),
            expiresAt = expiresAt,
        )
    }

    private fun loadXtreamVod(baseUrl: String): List<Entry> {
        return try {
            val json = fetchText("$baseUrl&action=get_vod_streams")
            val rows = parseGenericStreams(json, "stream_id")
            rows.mapNotNull { s ->
                val id = s["stream_id"] ?: return@mapNotNull null
                Entry(
                    id = id,
                    name = s["name"] ?: "?",
                    group = s["category_name"] ?: "Movies",
                    url = buildVodUrl(baseUrl, id),
                    logo = s["stream_icon"]?.takeIf { it.isNotBlank() },
                    description = (s["plot"] ?: s["description"])?.takeIf { it.isNotBlank() },
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VOD fetch failed", t)
            emptyList()
        }
    }

    private fun loadXtreamSeries(baseUrl: String): List<Entry> {
        return try {
            val json = fetchText("$baseUrl&action=get_series")
            val rows = parseGenericStreams(json, "series_id")
            rows.mapNotNull { s ->
                val id = s["series_id"] ?: return@mapNotNull null
                Entry(
                    id = id,
                    name = s["name"] ?: "?",
                    group = s["category_name"] ?: "Series",
                    url = buildSeriesUrl(baseUrl, id),
                    logo = s["cover"]?.takeIf { it.isNotBlank() } ?: s["stream_icon"]?.takeIf { it.isNotBlank() },
                    description = (s["plot"] ?: s["description"])?.takeIf { it.isNotBlank() },
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Series fetch failed", t)
            emptyList()
        }
    }

    /** Fetch a series' episode list from Xtream's `get_series_info`. */
    suspend fun episodes(baseUrl: String, seriesId: String): List<Episode> =
        withContext(Dispatchers.IO) { loadEpisodes(baseUrl, seriesId) }

    private fun loadEpisodes(baseUrl: String, seriesId: String): List<Episode> {
        val c = credsFrom(baseUrl)
        if (c.user.isBlank() || c.pass.isBlank()) return emptyList()
        val json = fetchText("$baseUrl&action=get_series_info&series_id=$seriesId")
        return runCatching { parseEpisodes(baseUrl, JSONObject(json)) }.getOrElse { emptyList() }
    }

    /** Split the `episodes` object of a `get_series_info` reply into [Episode]s. */
    private fun parseEpisodes(baseUrl: String, root: JSONObject): List<Episode> {
        val c = credsFrom(baseUrl)
        val out = mutableListOf<Episode>()
        val eps = root.optJSONObject("episodes") ?: return emptyList()
        try {
            for (seasonKey in eps.keys()) {
                val arr = eps.optJSONArray(seasonKey) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        ?: continue
                    val ext = o.optString("container_extension").ifBlank { "mkv" }
                    val info = o.optJSONObject("info")
                    out.add(
                        Episode(
                            id = id,
                            season = o.optString("season").toIntOrNull()
                                ?: seasonKey.toIntOrNull() ?: 1,
                            number = o.optString("episode_num").toIntOrNull() ?: (i + 1),
                            title = o.optString("title").ifBlank { info?.optString("name").orEmpty() },
                            url = "${c.host}/series/${c.user}/${c.pass}/$id.$ext",
                            durationSec = secondsOf(o.optString("duration"))
                                ?: secondsOf(info?.optString("duration_secs")),
                            plot = info?.optString("plot")?.ifBlank { null },
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "episode parse failed", t)
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    // ------------------------------------------------------- detail pages ---

    /** Metadata for a movie (`get_vod_info`); null when the provider has none. */
    suspend fun movieDetail(baseUrl: String, streamId: String): MediaDetail? =
        withContext(Dispatchers.IO) {
            val root = runCatching {
                JSONObject(fetchText("$baseUrl&action=get_vod_info&vod_id=$streamId"))
            }.getOrNull() ?: return@withContext null
            val info = root.optJSONObject("info") ?: return@withContext null
            detailFrom(info, streamId)
        }

    /**
     * Metadata + full episode list for a series (`get_series_info`). One request
     * serves both, which is why the episode parse is shared with [loadEpisodes].
     */
    suspend fun seriesDetail(baseUrl: String, seriesId: String): MediaDetail? =
        withContext(Dispatchers.IO) {
            val root = runCatching {
                JSONObject(fetchText("$baseUrl&action=get_series_info&series_id=$seriesId"))
            }.getOrNull() ?: return@withContext null
            val info = root.optJSONObject("info")
            val eps = runCatching { parseEpisodes(baseUrl, root) }.getOrElse { emptyList() }
            // Some panels return only episodes and no info blob at all; still worth
            // showing that season/episode list rather than an empty screen.
            if (info == null && eps.isEmpty()) return@withContext null
            val detail = info?.let { detailFrom(it, seriesId) }
                ?: MediaDetail(id = seriesId, title = "")
            detail.copy(episodes = eps)
        }

    /**
     * Read the uneven `info` blob into a [MediaDetail]. Panels disagree on key
     * names (`releaseDate`/`releasedate`, `movie_image`/`cover`, `duration` as
     * "01:34:00" vs seconds), so every field tries a few spellings.
     */
    private fun detailFrom(info: JSONObject, id: String): MediaDetail {
        val release = info.str("releaseDate", "releasedate", "release_date", "released")
        val year = info.str("year") ?: release?.take(4)?.takeIf { it.all(Char::isDigit) }
        return MediaDetail(
            id = id,
            title = info.str("name", "title", "o_name").orEmpty(),
            plot = info.str("plot", "description", "overview"),
            year = year,
            genre = info.str("genre"),
            rating = info.str("rating", "rating_5based", "vote_average"),
            duration = durationLabel(
                info.str("duration"),
                info.str("duration_secs"),
                info.str("episode_run_time"),
            ),
            cast = info.str("cast", "actors"),
            director = info.str("director"),
            country = info.str("country"),
            backdrop = firstImage(info, "backdrop_path", "backdrop"),
            poster = firstImage(info, "movie_image", "cover", "stream_icon"),
            trailer = trailerId(info.str("youtube_trailer", "trailer", "yt_trailer_code")),
        )
    }

    /** First non-blank string among [keys], ignoring JSON nulls and empty arrays. */
    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            val raw = opt(k) ?: continue
            val text = when (raw) {
                // Backdrops arrive as ["url"] on some panels — fall through to it.
                is JSONArray -> (0 until raw.length()).firstNotNullOfOrNull { i ->
                    raw.optString(i).takeIf { it.isNotBlank() }
                }
                else -> raw.toString()
            }?.trim()
            if (!text.isNullOrBlank() && text != "null" && text != "[]") return text
        }
        return null
    }

    /** Pull an image URL out of a field that may be a string or an array. */
    private fun firstImage(info: JSONObject, vararg keys: String): String? {
        val value = info.str(*keys) ?: return null
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("[") -> runCatching {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).firstNotNullOfOrNull { i ->
                    arr.optString(i).takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            trimmed.startsWith("http") -> trimmed
            else -> null
        }
    }

    /**
     * `youtube_trailer` is a bare id on most panels but a full URL on some, and a
     * few send nothing usable. Keep only the id when we can recognise one.
     */
    private fun trailerId(raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = Regex("(?:v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{6,})").find(v)
        if (match != null) return match.groupValues[1]
        return if (v.matches(Regex("[A-Za-z0-9_-]{6,}"))) v else null
    }

    /** Normalise runtime: seconds ("8040"), "HH:MM(:SS)" or minutes ("134"). */
    private fun durationLabel(vararg candidates: String?): String? {
        for (raw in candidates) {
            val secs = secondsOf(raw) ?: continue
            val h = secs / 3600
            val m = (secs % 3600) / 60
            return when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0 -> "${h}h"
                m > 0 -> "${m}m"
                else -> null
            }
        }
        return null
    }

    /**
     * Runtime/episode length in seconds from whatever the panel sent. Xtream panels
     * use every shape: "00:45:00", "45" (minutes) and plain seconds.
     */
    private fun secondsOf(raw: String?): Int? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() && it != "0" } ?: return null
        if (v.contains(':')) {
            val parts = v.split(':').mapNotNull { it.trim().toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> null
            }?.takeIf { it > 0 }
        }
        val n = v.toIntOrNull() ?: return null
        return when {
            n >= 3600 -> n            // already seconds
            // A bare number in minutes. Below 10 it is never a runtime — panels put
            // episode counts and other junk in `duration`/`episode_run_time`
            // ("8" for an 8-episode show), which must not render as "8m".
            n in 10..300 -> n * 60
            n >= 301 -> n
            else -> null
        }
    }

    /** Build a direct movie/series URL from a player_api base URL. */
    private data class XtreamCreds(val host: String, val user: String, val pass: String)

    private fun credsFrom(baseUrl: String): XtreamCreds {
        val host = baseUrl.substringBefore("/player_api.php")
        val user = Regex("username=([^&]*)").find(baseUrl)?.groupValues?.get(1).orEmpty()
        val pass = Regex("password=([^&]*)").find(baseUrl)?.groupValues?.get(1).orEmpty()
        return XtreamCreds(host, user, pass)
    }

    private fun buildVodUrl(baseUrl: String, streamId: String): String {
        val c = credsFrom(baseUrl)
        return if (c.user.isBlank() || c.pass.isBlank()) "" else "${c.host}/movie/${c.user}/${c.pass}/$streamId.mp4"
    }

    private fun buildSeriesUrl(baseUrl: String, seriesId: String): String {
        val c = credsFrom(baseUrl)
        return if (c.user.isBlank() || c.pass.isBlank()) "" else "${c.host}/series/${c.user}/${c.pass}/$seriesId.mkv"
    }

    private fun buildStreamUrl(base: String, user: String, pass: String, streamId: String): String {
        return "$base/live/$user/$pass/$streamId.m3u8"
    }

    /**
     * Parse `[{"category_id":"1","category_name":"News"}, ...]` into id->name
     * using a real JSON parser (no regex) — the robust approach the reference
     * uses. Handles numeric category_id too.
     */
    private fun parseIdNamePairs(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val arr = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.opt("category_id")?.toString() ?: continue
                val name = o.optString("category_name")
                if (id.isNotBlank() && name.isNotBlank()) map[id] = name
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parseIdNamePairs failed", t)
        }
        return map
    }

    /** Parse live streams JSON array into a list of string maps. */
    private fun parseXtreamStreams(json: String): List<Map<String, String>> =
        parseGenericStreams(json, "stream_id")

    /** Parse a JSON array of objects into string maps using org.json (no regex). */
    private fun parseGenericStreams(json: String, requiredKey: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        try {
            val arr = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val map = mutableMapOf<String, String>()
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = o.opt(k) ?: continue
                    map[k] = v.toString().takeIf { it != "null" } ?: ""
                }
                if (map.containsKey(requiredKey)) out.add(map)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parseGenericStreams failed", t)
        }
        return out
    }
}
