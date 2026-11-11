package com.mytv.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the app's playlist state in memory and persists it to SharedPreferences
 * so added sources survive restarts.
 */
class PlaylistRepository(context: Context) {

    private val prefs = context.getSharedPreferences("mytv_playlists", Context.MODE_PRIVATE)

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
    }

    fun setActive(name: String) {
        val updated = _playlists.value.map { it.copy(active = it.name == name) }
        _playlists.value = updated
        _activePlaylistId.value = name
        persist(updated)
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
        // playlists are stored under fixed keys; collect them
        // We can't enumerate keys easily, so persist a manifest list too.
        val manifest = prefs.getString("manifest", "[]")
        val order = try { JSONArray(manifest) } catch (t: Throwable) { JSONArray() }
        for (i in 0 until order.length()) {
            val name = order.getString(i)
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
