package com.mytv.app.data

/** A single channel (live TV) parsed from M3U or Xtream. */
data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val logo: String? = null,
    val number: Int = 0,
)

/** A movie / series entry from a playlist (Xtream VOD or M3U movie group). */
data class Entry(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val logo: String? = null,
    val description: String? = null,
)

/** EPG-style "now playing" label for a channel (from #EXTINF attrs or EPG XMLTV). */
data class NowPlaying(val channelId: String, val title: String, val startLabel: String, val progress: Float)

/** A playlist source the user added. */
data class Playlist(
    val name: String,
    val kind: PlaylistKind,
    val url: String,
    val user: String? = null,
    val pass: String? = null,
    val active: Boolean = true,
    val channels: List<Channel> = emptyList(),
    val movies: List<Entry> = emptyList(),
    val series: List<Entry> = emptyList(),
)

enum class PlaylistKind { M3U, XTREAM }
