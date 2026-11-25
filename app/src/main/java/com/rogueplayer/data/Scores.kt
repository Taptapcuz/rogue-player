package com.rogueplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One game on the slate, in the shape both UIs draw.
 *
 * Scores are shown as reference information: team names as text, no logos, and the
 * broadcast name is what the playlist gets matched against when the card is tapped.
 */
data class ScoreGame(
    val league: String,
    val away: String,
    val awayAbbr: String,
    val awayScore: String?,
    val home: String,
    val homeAbbr: String,
    val homeScore: String?,
    /** Short status for the card: "LIVE", "FINAL", "8:00 PM". */
    val status: String,
    /** Extra line next to a live status: "Q3 05:12", "3rd 1:44". */
    val detail: String?,
    /** Week / round / holiday line under the teams, when ESPN gives one. */
    val note: String?,
    val broadcasts: List<String>,
    /** ESPN team logo, when the feed supplies one (falls back to our own badge). */
    val awayLogo: String? = null,
    val homeLogo: String? = null,
    val live: Boolean,
    val completed: Boolean,
    val startMs: Long,
)

/**
 * ESPN's public scoreboard — no key, no account.
 *
 * Unofficial: it can change or block us without notice, so every failure is swallowed
 * and the last good slate is served from memory. Everything the app does with scores
 * goes through here, so swapping to a licensed feed later is one file, not a rewrite.
 */
object ScoreSource {

    /** League → ESPN path. Anything not listed is skipped. */
    private val LEAGUES = linkedMapOf(
        "NFL" to "football/nfl",
        "NCAAF" to "football/college-football",
        "NBA" to "basketball/nba",
        "NCAAB" to "basketball/mens-college-basketball",
        "MLB" to "baseball/mlb",
        "NHL" to "hockey/nhl",
    )

    private val DEFAULT_LEAGUES = listOf("NFL", "NCAAF", "NBA", "NCAAB", "MLB", "NHL")

    private data class Cached(val at: Long, val games: List<ScoreGame>)

    @Volatile
    private var cache: Cached? = null

    // ESPN sends UTC ("2026-11-25T01:00Z"). Parse it AS UTC — treating the wall clock as
    // local shifted every kickoff by the device's offset (5 PM Pacific showed as 1 AM).
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val isoZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = utc }
    private val isoZS = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = utc }
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
    private val dayFmt = SimpleDateFormat("EEE", Locale.US)

    /**
     * The slate for [leagues] (default: the four majors), newest first: live games,
     * then upcoming, then finished. Serves the previous slate when the network fails.
     */
    suspend fun slate(
        leagues: List<String> = DEFAULT_LEAGUES,
        maxAgeMs: Long = 60_000L,
    ): List<ScoreGame> = withContext(Dispatchers.IO) {
        val hit = cache
        if (hit != null && System.currentTimeMillis() - hit.at < maxAgeMs) return@withContext hit.games

        val games = ArrayList<ScoreGame>()
        var anyOk = false
        for (league in leagues) {
            val path = LEAGUES[league] ?: continue
            val body = runCatching { get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard") }
                .getOrNull()
            if (body == null) continue
            anyOk = true
            runCatching { games += parse(league, body) }
        }
        if (!anyOk) return@withContext hit?.games.orEmpty()

        val sorted = games.sortedWith(
            compareByDescending<ScoreGame> { it.live }
                .thenByDescending { !it.completed }
                .thenBy { if (it.completed) -it.startMs else it.startMs },
        )
        cache = Cached(System.currentTimeMillis(), sorted)
        sorted
    }

    /** Games whose broadcast matches [needle] (a playlist channel name), newest first. */
    fun matchesBroadcast(games: List<ScoreGame>, channelName: String): List<ScoreGame> {
        val want = channelName.uppercase(Locale.US)
        return games.filter { g -> g.broadcasts.any { b -> want.contains(b.uppercase(Locale.US)) } }
    }

    /**
     * ESPN's scoreboard carries the team mark as `team.logo` (a bare URL); a few feeds use
     * a `logos[]` array instead. Read both — reading only the array left every card on our
     * drawn fallback badge.
     */
    private fun logoOf(team: JSONObject?): String? {
        val t = team ?: return null
        t.optString("logo").takeIf { it.isNotBlank() }?.let { return it }
        val arr = t.optJSONArray("logos") ?: return null
        return arr.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RoguePlayer")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(league: String, body: String): List<ScoreGame> {
        val root = JSONObject(body)
        val events = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<ScoreGame>(events.length())
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: continue

            var away: JSONObject? = null
            var home: JSONObject? = null
            val teams = comp.optJSONArray("competitors")
            if (teams != null) {
                for (j in 0 until teams.length()) {
                    val t = teams.optJSONObject(j) ?: continue
                    if (t.optString("homeAway") == "home") home = t else away = t
                }
            }
            if (away == null || home == null) continue

            val st = comp.optJSONObject("status")?.optJSONObject("type")
            val state = st?.optString("state").orEmpty()      // pre | in | post
            val completed = st?.optBoolean("completed", false) ?: (state == "post")
            val live = state == "in"
            val raw = ev.optString("date")
            val start = (runCatching { isoZ.parse(raw)?.time }.getOrNull()
                ?: runCatching { isoZS.parse(raw)?.time }.getOrNull())
                ?: System.currentTimeMillis()
            val startDate = Date(start)

            val status = when {
                live -> "LIVE"
                completed -> "FINAL"
                else -> dayFmt.format(startDate) + " " + timeFmt.format(startDate)
            }
            val detail = when {
                live -> st?.optString("shortDetail")?.substringAfter(" - ", "")?.ifBlank { null }
                completed -> st?.optString("shortDetail")?.takeIf { it.length in 3..18 }
                else -> null
            }

            // Only a headline we can actually read: the season slug ("regular-season") used
            // to render as a bare "NFL · Week".
            val note = comp.optJSONObject("notes")?.let { n ->
                n.optJSONArray("headline")?.optString(0) ?: n.optString("headline").ifBlank { null }
            }?.takeIf { it.isNotBlank() }

            val broadcasts = ArrayList<String>()
            val br = comp.optJSONArray("broadcasts")
            if (br != null) {
                for (j in 0 until br.length()) {
                    val names = br.optJSONObject(j)?.optJSONArray("names") ?: continue
                    for (k in 0 until names.length()) {
                        names.optString(k).takeIf { it.isNotBlank() }?.let { broadcasts += it }
                    }
                }
            }
            if (broadcasts.isEmpty()) {
                // Fall back to the flat "broadcast" field some leagues still send.
                comp.optString("broadcast").takeIf { it.isNotBlank() }?.let { broadcasts += it }
            }

            out += ScoreGame(
                league = league,
                away = away.optJSONObject("team")?.optString("displayName").orEmpty().ifBlank { "Away" },
                awayAbbr = away.optJSONObject("team")?.optString("abbreviation").orEmpty()
                    .ifBlank { away.optJSONObject("team")?.optString("shortDisplayName").orEmpty() },
                awayScore = away.optString("score").takeIf { it.isNotBlank() && it != "0" || completed || live },
                home = home.optJSONObject("team")?.optString("displayName").orEmpty().ifBlank { "Home" },
                homeAbbr = home.optJSONObject("team")?.optString("abbreviation").orEmpty()
                    .ifBlank { home.optJSONObject("team")?.optString("shortDisplayName").orEmpty() },
                homeScore = home.optString("score").takeIf { it.isNotBlank() && it != "0" || completed || live },
                status = status,
                detail = detail,
                note = note,
                broadcasts = broadcasts.distinct(),
                awayLogo = logoOf(away.optJSONObject("team")),
                homeLogo = logoOf(home.optJSONObject("team")),
                live = live,
                completed = completed,
                startMs = start,
            )
        }
        return out
    }
}
