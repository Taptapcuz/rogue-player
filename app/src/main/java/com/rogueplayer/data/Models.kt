package com.rogueplayer.data

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

/**
 * Rich metadata for one movie or series, from Xtream `get_vod_info` /
 * `get_series_info`. Every field is optional: panels fill these in unevenly, and
 * the detail screen has to render whatever did come back instead of a blank page.
 */
data class MediaDetail(
    val id: String,
    val title: String,
    val plot: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    /** Human-readable runtime ("2h 14m"), or null when the provider sent none. */
    val duration: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val country: String? = null,
    val backdrop: String? = null,
    val poster: String? = null,
    /** YouTube id or URL — `youtube_trailer` on most panels. */
    val trailer: String? = null,
    val episodes: List<Episode> = emptyList(),
) {
    /** Season numbers present in [episodes], ascending. */
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()

    fun episodesOf(season: Int): List<Episode> = episodes.filter { it.season == season }
}

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
    /**
     * Account expiry as reported by the provider's `user_info.exp_date` (epoch millis),
     * null when the panel doesn't report one (or the line never expires).
     */
    val expiresAt: Long? = null,
)

enum class PlaylistKind { M3U, XTREAM }
