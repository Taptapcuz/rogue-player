package com.rogueplayer.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.ListTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rogueplayer.MyApplication
import com.rogueplayer.data.Episode
import com.rogueplayer.data.Entry
import com.rogueplayer.data.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Car browse screens behind the landing's Movies / TV Shows / TV Guide / Playlists
 * tiles. Each is a standard AA ListTemplate (saner for a head unit than projecting
 * the phone's poster grids) built from the shared [PlaylistRepository].
 *
 * Playback and the fullscreen video screen are injected by the session
 * ([play] starts a stream, [openPlayer] pushes the video screen onto [host]),
 * so these screens stay agnostic of the session's internal screen wiring.
 */

private val TAG = "CarBrowse"

private fun repoOf(ctx: CarContext): PlaylistRepository =
    (ctx.applicationContext as MyApplication).repository

// ------------------------------------------------------------------ movies ---

/** Movie categories (the landing's Movies tile). */
class CarMovieScreen(
    private val ctx: CarContext,
    private val play: (url: String, title: String) -> Unit,
    private val openPlayer: (host: Screen, title: String) -> Unit,
) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val pl = repoOf(ctx).activePlaylist
        val list = ItemList.Builder()
        if (pl == null || pl.movies.isEmpty()) {
            list.addItem(Row.Builder()
                .setTitle(if (pl == null) "No playlist loaded" else "No movies")
                .addText("Add an M3U or Xtream source from the phone app.")
                .build())
        } else {
            val hidden = repoOf(ctx).hiddenGroups(pl.name)
            pl.movies.filterNot { it.group in hidden }
                .groupBy { it.group.ifBlank { "Ungrouped" } }
                .toSortedMap().forEach { (group, movies) ->
                    list.addItem(Row.Builder()
                        .setTitle(group)
                        .addText("${movies.size} movies")
                        .setOnClickListener {
                            screenManager.push(
                                CarMovieListScreen(ctx, play, openPlayer, group, movies))
                        }
                        .build())
                }
        }
        return ListTemplate.Builder()
            .setTitle("Movies")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/** One movie category's titles; tap plays. */
class CarMovieListScreen(
    private val ctx: CarContext,
    private val play: (url: String, title: String) -> Unit,
    private val openPlayer: (host: Screen, title: String) -> Unit,
    private val group: String,
    private val movies: List<Entry>,
) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        movies.take(MAX_ROWS).forEach { e ->
            list.addItem(Row.Builder()
                .setTitle(e.name)
                .apply { e.group.ifBlank { null }?.let { addText(it) } }
                .setOnClickListener { start(e) }
                .build())
        }
        return ListTemplate.Builder()
            .setTitle("Movies — $group")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun start(e: Entry) {
        if (e.url.isBlank()) return
        play(e.url, e.name)
        openPlayer(this, e.name)
    }
}

// ------------------------------------------------------------------- shows ---

/** Series categories (the landing's TV Shows tile). */
class CarShowScreen(
    private val ctx: CarContext,
    private val play: (url: String, title: String) -> Unit,
    private val openPlayer: (host: Screen, title: String) -> Unit,
) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val pl = repoOf(ctx).activePlaylist
        val list = ItemList.Builder()
        if (pl == null || pl.series.isEmpty()) {
            list.addItem(Row.Builder()
                .setTitle(if (pl == null) "No playlist loaded" else "No TV shows")
                .addText("Add an M3U or Xtream source from the phone app.")
                .build())
        } else {
            val hidden = repoOf(ctx).hiddenGroups(pl.name)
            pl.series.filterNot { it.group in hidden }
                .groupBy { it.group.ifBlank { "Ungrouped" } }
                .toSortedMap().forEach { (group, shows) ->
                    list.addItem(Row.Builder()
                        .setTitle(group)
                        .addText("${shows.size} shows")
                        .setOnClickListener {
                            screenManager.push(
                                CarSeriesListScreen(ctx, play, openPlayer, group, shows))
                        }
                        .build())
                }
        }
        return ListTemplate.Builder()
            .setTitle("TV Shows")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/** One series category's shows; tapping opens its episodes. */
class CarSeriesListScreen(
    private val ctx: CarContext,
    private val play: (url: String, title: String) -> Unit,
    private val openPlayer: (host: Screen, title: String) -> Unit,
    private val group: String,
    private val shows: List<Entry>,
) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        shows.take(MAX_ROWS).forEach { e ->
            list.addItem(Row.Builder()
                .setTitle(e.name)
                .setOnClickListener { pushEpisodes(e) }
                .build())
        }
        return ListTemplate.Builder()
            .setTitle("TV Shows — $group")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    /** Load episodes off the main thread, then slide the list in. */
    private fun pushEpisodes(series: Entry) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        s.launch {
            val eps = repoOf(ctx).episodesFor(series)
            s.cancel()
            screenManager.push(CarEpisodesScreen(ctx, play, openPlayer, series, eps))
        }
    }
}

/** A series' episodes, grouped by season; tapping an episode plays it. */
class CarEpisodesScreen(
    private val ctx: CarContext,
    private val play: (url: String, title: String) -> Unit,
    private val openPlayer: (host: Screen, title: String) -> Unit,
    private val series: Entry,
    private val episodes: List<Episode>,
) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (episodes.isEmpty()) {
            list.addItem(Row.Builder().setTitle("No episodes")
                .addText("This provider returned no episode list.").build())
        } else {
            val bySeason = episodes.groupBy { it.season }.toSortedMap()
            bySeason.forEach { (season, eps) ->
                // One header row per season, then each episode.
                list.addItem(Row.Builder()
                    .setTitle("Season $season")
                    .addText("${eps.size} episodes")
                    .build())
                eps.take(200).forEach { ep ->
                    list.addItem(Row.Builder()
                        .setTitle(ep.displayTitle())
                        .setOnClickListener { start(ep) }
                        .build())
                }
            }
        }
        return ListTemplate.Builder()
            .setTitle(series.name)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun start(ep: Episode) {
        if (ep.url.isBlank()) return
        play(ep.url, "${series.name} — ${ep.displayTitle()}")
        openPlayer(this, "${series.name} — ${ep.displayTitle()}")
    }
}

private fun Episode.displayTitle(): String =
    if (title.isNotBlank()) title
    else if (number > 0) "Episode $number"
    else "Episode"

private const val MAX_ROWS = 60
