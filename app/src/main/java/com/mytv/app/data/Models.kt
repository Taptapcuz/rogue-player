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

/**
 * A single EPG programme with absolute time bounds (epoch millis).
 * [title] and [description] are already decoded (base64 → UTF-8 when present).
 */
data class EpgProgram(
    val start: Long,
    val end: Long,
    val title: String,
    val description: String? = null,
    val icon: String? = null,
    val archive: Boolean = false,
) {
    /** A programme is usable only if it has a real, ordered time range. */
    fun isValid(): Boolean = start > 0L && end > start

    /** True when [now] (epoch millis) falls inside this programme's slot. */
    fun contains(now: Long): Boolean = now >= start && now < end
}

/** One episode of a series (Xtream `get_series_info`). */
data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val durationSec: Int? = null,
    val plot: String? = null,
)

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
