package com.mytv.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the app's playlist state in memory and persists it to SharedPreferences
 * so added sources survive restarts.
 */
class PlaylistRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("mytv_playlists", Context.MODE_PRIVATE)

    /** Guide listings persisted per (playlist, stream), TiViMate style. */
    private val epgDisk by lazy { EpgCache(appContext) }
    private var epgPruned = false

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _activePlaylistId = MutableStateFlow<String?>(null)
    val activePlaylistId: StateFlow<String?> = _activePlaylistId.asStateFlow()

    init {
        loadFromPrefs().forEach {
            _playlists.value = _playlists.value + it
        }
        if (_activePlaylistId.value == null && _playlists.value.isNotEmpty()) {
            _activePlaylistId.value = _playlists.value.first().name
        }
    }

    val activePlaylist: Playlist?
        get() = _playlists.value.firstOrNull { it.name == _activePlaylistId.value }

    fun add(playlist: Playlist) {
        // deactivate others
        val activeName = playlist.name
        val updated = _playlists.value.map { it.copy(active = false) } + playlist.copy(active = true)
        _playlists.value = updated
        _activePlaylistId.value = activeName
        persist(updated)
        epgData.clear()
    }

    fun setActive(name: String) {
        val updated = _playlists.value.map { it.copy(active = it.name == name) }
        _playlists.value = updated
        _activePlaylistId.value = name
        persist(updated)
        epgData.clear()
    }

    /**
     * Swap a stored playlist's contents after a refresh, keeping its position in
     * the list and its active flag. Name stays the key everything else uses.
     */
    fun replaceContents(name: String, fresh: Playlist) {
        val updated = _playlists.value.map { pl ->
            if (pl.name == name) fresh.copy(name = name, active = pl.active) else pl
        }
        _playlists.value = updated
        persist(updated)
        // Channel ids may have moved, so the per-stream guide memo is no longer safe.
        epgData.clear()
    }

    fun remove(name: String) {
        val updated = _playlists.value.filterNot { it.name == name }
        _playlists.value = updated
        if (_activePlaylistId.value == name) {
            _activePlaylistId.value = updated.firstOrNull()?.name
        }
        prefs.edit().remove("playlist:$name").remove("active").apply()
        persist(updated)
    }

    // ------------------------------------------------------------- EPG ---

    /** Per (playlist, stream) EPG memo so switching channels doesn't re-fetch. */
    private val epgCache = Mutex()
    private val epgData = mutableMapOf<String, List<EpgProgram>>()

    private fun epgKey(playlist: Playlist, streamId: String) = "${playlist.url}|$streamId"

    /**
     * Load the guide for [channel] from the [active] Xtream playlist.
     *
     * Order of preference, like TiViMate: hot memory memo → fresh disk cache (so a
     * restart or a scrolling guide is instant) → provider → stale disk cache. A
     * dead provider therefore leaves yesterday's/this morning's listings on the
     * grid instead of "No guide data" everywhere.
     */
    suspend fun epgFor(channel: Channel): List<EpgProgram> {
        val pl = activePlaylist ?: return emptyList()
        if (pl.kind != PlaylistKind.XTREAM || pl.url.isBlank()) return emptyList()

        val key = epgKey(pl, channel.id)
        epgCache.withLock {
            epgData[key]?.let { return it }
        }
        if (!epgPruned) {
            epgPruned = true
            epgDisk.prune()
        }

        val now = System.currentTimeMillis()
        val cached = epgDisk.read(key)
        if (cached != null && now - cached.fetchedAt < EPG_FRESH_MS &&
            cached.programs.any { it.end > now }
        ) {
            epgCache.withLock { epgData[key] = cached.programs }
            return cached.programs
        }

        val fetched = runCatching { EpgParser.load(pl.url, channel.id) }
            .getOrNull().orEmpty()
        if (fetched.isNotEmpty()) {
            epgDisk.write(key, fetched, now)
            epgCache.withLock { epgData[key] = fetched }
            return fetched
        }
        // Nothing from the provider (reset/outage or no listings): fall back to
        // whatever we last stored rather than showing an empty row.
        if (cached != null) {
            epgCache.withLock { epgData[key] = cached.programs }
            return cached.programs
        }
        return emptyList()
    }

    /** The programme currently airing on [channel] (or null with no EPG loaded). */
    suspend fun nowPlaying(channel: Channel, time: Long = System.currentTimeMillis()): EpgProgram? =
        epgFor(channel).firstOrNull { it.contains(time) }

    /** The programme after the one currently airing on [channel] (or null). */
    suspend fun nextPlaying(channel: Channel, time: Long = System.currentTimeMillis()): EpgProgram? {
        val epg = epgFor(channel)
        val idx = epg.indexOfFirst { it.contains(time) }
        if (idx < 0 || idx + 1 >= epg.size) return null
        return epg[idx + 1]
    }

    /** Drop all cached EPG (e.g. when the active playlist changes). */
    suspend fun clearEpgCache() {
        epgCache.withLock { epgData.clear() }
        epCache.clear()
        epgDisk.clear()
    }

    private companion object {
        /** How long a stored guide is trusted before asking the provider again. */
        const val EPG_FRESH_MS = 4L * 3600_000L
    }

    // ---------------------------------------------------------- series ---

    /** Episode lists are per series and change rarely; memo them. */
    private val epCache = mutableMapOf<String, List<Episode>>()

    /**
     * Episodes of [series] from the active Xtream playlist. Empty for M3U
     * sources, which have no series API.
     */
    suspend fun episodesFor(series: Entry): List<Episode> {
        val pl = activePlaylist ?: return emptyList()
        if (pl.kind != PlaylistKind.XTREAM || pl.url.isBlank()) return emptyList()
        val key = "${pl.url}|${series.id}"
        epCache[key]?.let { return it }
        val eps = PlaylistParser.episodes(pl.url, series.id)
        epCache[key] = eps
        return eps
    }

    private fun persist(list: List<Playlist>) {
        val manifest = JSONArray(list.map { it.name })
        prefs.edit()
            .putString("active", _activePlaylistId.value)
            .putString("manifest", manifest.toString())
            .apply()
        list.forEach { pl ->
            val json = JSONObject().apply {
                put("name", pl.name)
                put("kind", pl.kind.name)
                put("url", pl.url)
                put("user", pl.user ?: "")
                put("pass", pl.pass ?: "")
                put("channels", JSONArray(pl.channels.map { c ->
                    JSONObject().apply {
                        put("id", c.id); put("name", c.name); put("group", c.group)
                        put("url", c.url); put("logo", c.logo ?: ""); put("number", c.number)
                    }
                }))
                put("movies", JSONArray(pl.movies.map { e -> entryJson(e) }))
                put("series", JSONArray(pl.series.map { e -> entryJson(e) }))
            }
            prefs.edit().putString("playlist:${pl.name}", json.toString()).apply()
        }
    }

    private fun entryJson(e: Entry) = JSONObject().apply {
        put("id", e.id); put("name", e.name); put("group", e.group)
        put("url", e.url); put("logo", e.logo ?: ""); put("description", e.description ?: "")
    }

    private fun loadFromPrefs(): List<Playlist> {
        val active = prefs.getString("active", null)
        val out = mutableListOf<Playlist>()
        // Playlists live under fixed keys, so a separate manifest carries the order.
        // If the manifest is lost/empty (a bad write, or a prefs file swapped under a
        // running process) fall back to the keys that are actually present, otherwise
        // the app starts with nothing and the next persist() wipes every source.
        val manifest = prefs.getString("manifest", "[]")
        val order = try { JSONArray(manifest) } catch (t: Throwable) { JSONArray() }
        val names = if (order.length() > 0) {
            (0 until order.length()).mapNotNull { runCatching { order.getString(it) }.getOrNull() }
        } else {
            prefs.all.keys
                .filter { it.startsWith("playlist:") }
                .map { it.removePrefix("playlist:") }
                .sorted()
        }
        for (name in names) {
            val raw = prefs.getString("playlist:$name", null) ?: continue
            try {
                val o = JSONObject(raw)
                val channels = (o.optJSONArray("channels") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { j ->
                        val c = arr.getJSONObject(j)
                        Channel(c.getString("id"), c.getString("name"), c.getString("group"),
                            c.getString("url"), c.optString("logo").ifBlank { null }, c.optInt("number"))
                    }
                }
                val movies = parseEntries(o.optJSONArray("movies"))
                val series = parseEntries(o.optJSONArray("series"))
                out.add(Playlist(
                    name = o.getString("name"),
                    kind = PlaylistKind.valueOf(o.optString("kind", "M3U")),
                    url = o.getString("url"),
                    user = o.optString("user").ifBlank { null },
                    pass = o.optString("pass").ifBlank { null },
                    active = name == active,
                    channels = channels, movies = movies, series = series,
                ))
            } catch (t: Throwable) {
                // skip corrupted row
            }
        }
        return out
    }

    private fun parseEntries(arr: JSONArray?): List<Entry> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { j ->
            val e = arr.getJSONObject(j)
            Entry(e.getString("id"), e.getString("name"), e.getString("group"),
                e.getString("url"), e.optString("logo").ifBlank { null }, e.optString("description").ifBlank { null })
        }
    }
}
