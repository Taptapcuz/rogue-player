package com.mytv.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
            .build()
    }

    /** Download a URL text body. */
    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "MYTV/1.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: ""
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
                        movies.add(Entry(id, name, group, chUrl))
                    } else if (group.equals("SERIES", true) || group.equals("SERIE", true)) {
                        series.add(Entry(id, name, group, chUrl))
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
        // strip trailing slash
        var base = host.trim().trimEnd('/')
        if (!base.startsWith("http")) base = "http://$base"
        val baseUrl = "$base/player_api.php?username=$user&password=$pass"

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
            name = host.substringAfter("://").substringBefore('/').ifBlank { "Xtream" },
            kind = PlaylistKind.XTREAM,
            url = baseUrl,
            user = user,
            pass = pass,
            channels = channels,
            movies = loadXtreamVod(baseUrl),
            series = loadXtreamSeries(baseUrl),
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

    /** Parse [{"category_id":"1","category_name":"News"}, ...] into id->name. */
    private fun parseIdNamePairs(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("\"category_id\"\\s*:\\s*\"?([^\",}]+)\"?\\s*,\\s*\"category_name\"\\s*:\\s*\"([^\"]+)\"")
        regex.findAll(json).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[2]
        }
        // fallback: handle category_name before category_id
        if (map.isEmpty()) {
            val regex2 = Regex("\"category_name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"category_id\"\\s*:\\s*\"?([^\",}]+)")
            regex2.findAll(json).forEach { m ->
                map[m.groupValues[2]] = m.groupValues[1]
            }
        }
        return map
    }

    /** Parse live streams JSON array into a list of string maps. */
    private fun parseXtreamStreams(json: String): List<Map<String, String>> {
        return parseGenericStreams(json, "stream_id")
    }

    /** Parse a JSON array of objects into string maps, filtering out method shells. */
    private fun parseGenericStreams(json: String, requiredKey: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        val objectRegex = Regex("\\{[^{}]*}")
        objectRegex.findAll(json).forEach { obj ->
            val map = mutableMapOf<String, String>()
            Regex("\"([a-zA-Z_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(obj.value).forEach { m ->
                if (!map.containsKey(m.groupValues[1])) map[m.groupValues[1]] = m.groupValues[2]
            }
            // also capture numeric id keys with no quotes around value
            Regex("\"${Regex.escape(requiredKey)}\"\\s*:\\s*\"?([^\",}]+)\"?").find(obj.value)?.let {
                if (!map.containsKey(requiredKey)) map[requiredKey] = it.groupValues[1]
            }
            if (map.containsKey(requiredKey)) out.add(map)
        }
        return out
    }
}
