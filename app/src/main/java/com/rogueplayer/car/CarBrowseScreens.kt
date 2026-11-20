package com.rogueplayer.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.ListTemplate
import androidx.core.graphics.drawable.IconCompat
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
                                if (apiAtLeast6(ctx)) {
                                    CarPosterGridScreen(ctx, "Movies — $group", movies) { e ->
                                        if (e.url.isNotBlank()) {
                                            play(e.url, e.name)
                                            openPlayer(this, e.name)
                                        }
                                    }
                                } else {
                                    CarMovieListScreen(ctx, play, openPlayer, group, movies)
                                })
                        }
                        .build())
                }
        }
        return ListTemplate.Builder()
            .setTitle("Movies")
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(searchIcon())
                            .setOnClickListener {
                                screenManager.push(
                                    CarLibrarySearchScreen(ctx, play, openPlayer, movies = true))
                            }
                            .build())
                    .build())
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
                                if (apiAtLeast6(ctx)) {
                                    CarPosterGridScreen(ctx, "TV Shows — $group", shows) { e ->
                                        pushEpisodes(ctx, screenManager, play, openPlayer, e)
                                    }
                                } else {
                                    CarSeriesListScreen(ctx, play, openPlayer, group, shows)
                                })
                        }
                        .build())
                }
        }
        return ListTemplate.Builder()
            .setTitle("TV Shows")
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(searchIcon())
                            .setOnClickListener {
                                screenManager.push(
                                    CarLibrarySearchScreen(ctx, play, openPlayer, movies = false))
                            }
                            .build())
                    .build())
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

/**
 * Poster grid for one category (Movies or TV Shows).
 *
 * AA's GridTemplate draws one image per item, but the host will NOT fetch a URL — every
 * poster has to arrive as a decoded Bitmap. [CarPosterCache] keeps an LRU of thumbnails;
 * the screen builds instantly with whatever is cached and calls invalidate() as the rest
 * land, so scrolling never blocks on the network.
 *
 * Only used when the host reports Car API 6+ (GridTemplate); older head units keep the
 * plain [CarMovieListScreen] / [CarSeriesListScreen] lists.
 */
class CarPosterGridScreen(
    private val ctx: CarContext,
    private val title: String,
    private val entries: List<Entry>,
    private val page: Int = 0,
    private val onPick: (Entry) -> Unit,
) : Screen(ctx) {

    private var refreshQueued = false
    private val handler = Handler(Looper.getMainLooper())

    private val from = (page * PAGE_SIZE).coerceIn(0, entries.size)
    private val to = (from + PAGE_SIZE).coerceAtMost(entries.size)
    private val pageItems = entries.subList(from, to)
    private val pageCount = ((entries.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)

    override fun onGetTemplate(): Template {
        queueMissing(pageItems)

        val list = ItemList.Builder()
        pageItems.forEach { e ->
            val bitmap = CarPosterCache.get(e.logo)
                ?: if (e.logo.isNullOrBlank()) CarPosterCache.placeholder(e.name) else null
            // GridItem.Builder throws when the item is (not loading AND has no image), and a
            // loading item may not carry a click listener — so an un-loaded poster becomes a
            // loading tile (no click), and only a real/placeholder image is tappable.
            //
            // No title: the car tile gives the image one narrow strip of height and the name
            // was costing most of the poster. Which film it is comes up on tap / in the title.
            val item = GridItem.Builder()
            if (bitmap != null) {
                item.setImage(
                    CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build(),
                    GridItem.IMAGE_TYPE_LARGE
                )
                item.setOnClickListener { onPick(e) }
            } else {
                item.setLoading(true)
            }
            list.addItem(item.build())
        }

        // Paging as tiles: the host only renders one page's worth, so a 6,000-title category
        // needs its own next/previous targets inside the grid.
        if (page > 0) list.addItem(navTile("\u2039 Previous", PageNav.PREV))
        if (to < entries.size) list.addItem(navTile("Next \u203A", PageNav.NEXT))

        Log.d(TAG, "grid '$title' page ${page + 1}/$pageCount: ${pageItems.size} items, " +
            "${pageItems.count { CarPosterCache.get(it.logo) != null }} cached, " +
            "carApi=${runCatching { ctx.carAppApiLevel }.getOrDefault(-1)}")
        val builder = GridTemplate.Builder()
            .setTitle(if (pageCount > 1) "$title (${page + 1}/$pageCount)" else title)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
        // ITEM_SIZE_LARGE needs Car API 7; older hosts keep the default tile size.
        if (runCatching { ctx.carAppApiLevel >= 7 }.getOrDefault(false)) {
            builder.setItemSize(GridTemplate.ITEM_SIZE_LARGE)
        }
        return builder.build()
    }

    private enum class PageNav { PREV, NEXT }

    /** A big arrow tile that walks to the neighbouring page of posters. */
    private fun navTile(label: String, nav: PageNav): GridItem {
        val target = if (nav == PageNav.NEXT) page + 1 else page - 1
        val bmp = CarPosterCache.navTile(label)
        return GridItem.Builder()
            .setImage(CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build(), GridItem.IMAGE_TYPE_LARGE)
            .setOnClickListener {
                screenManager.push(
                    CarPosterGridScreen(ctx, title, entries, target, onPick)
                )
            }
            .build()
    }

    /** Fetch whatever this page needs, then rebuild once when the last one lands. */
    private fun queueMissing(items: List<Entry>) {
        if (refreshQueued) return
        val missing = items.filter { CarPosterCache.get(it.logo) == null && !it.logo.isNullOrBlank() }
        if (missing.isEmpty()) return
        refreshQueued = true
        var pending = missing.size
        missing.forEach { e ->
            CarPosterCache.load(e.logo) {
                pending--
                if (pending <= 0) {
                    refreshQueued = false
                    // Post: a cache hit completes synchronously inside onGetTemplate, and
                    // invalidate() from there would re-enter the template build.
                    handler.post {
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                            invalidate()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Decoded poster thumbnails for the car grids.
 *
 * Grids redraw on every invalidate, so this must never touch the network on the calling
 * thread: [get] is LRU-only, [load] does the fetch on a small pool and notifies on main.
 */
private object CarPosterCache {

    // sizeOf() below returns KILOBYTES, so the cap must be in KB too: 64 * 1024 = 64 MB.
    // (56 here meant 56 KB, which evicted every poster the instant it was cached.)
    private val cache = object : LruCache<String, Bitmap>(64 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** URLs that already failed this session — retrying them every rebuild just loops. */
    private val failed = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun get(url: String?): Bitmap? {
        val key = url?.takeIf { it.isNotBlank() } ?: return null
        return cache.get(key)
    }

    /**
     * Tile used when an entry has no poster URL at all.
     *
     * GridItem refuses to build an item that has neither an image nor `setLoading(true)`, and
     * a loading item cannot be tapped — so entries without artwork need a real bitmap to stay
     * clickable. Drawn once per title and cached under a "ph:" key.
     */
    fun placeholder(title: String): Bitmap {
        val key = "ph:${title.take(1).uppercase()}${title.hashCode()}"
        cache.get(key)?.let { return it }
        val w = 512; val h = 512
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val hue = (title.hashCode() and 0x7FFFFFFF) % 360
        val fill = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.32f))
        canvas.drawColor(fill)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 200f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        val initial = title.trim().take(1).uppercase().ifBlank { "?" }
        val y = h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, w / 2f, y, paint)
        cache.put(key, bmp)
        return bmp
    }

    fun load(url: String?, onDone: () -> Unit) {
        val key = url?.takeIf { it.isNotBlank() }
        if (key == null || cache.get(key) != null || key in failed) { onDone(); return }
        pool.execute {
            val bitmap = runCatching {
                client.newCall(Request.Builder().url(key).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }
            }.onFailure { Log.w(TAG, "poster fetch failed $key: ${it.message}") }
                .getOrNull()?.let { scale(it) }
            if (bitmap == null) {
                failed.add(key)
                Log.w(TAG, "poster decode failed or empty: $key")
            }
            main.post {
                bitmap?.let { cache.put(key, it) }
                onDone()
            }
        }
    }

    /**
     * Arrow tile used for page navigation inside a poster grid — a GridItem with no image and
     * no loading flag is illegal, so the chevron is drawn as a real bitmap.
     */
    fun navTile(label: String): Bitmap {
        val key = "nav:$label"
        cache.get(key)?.let { return it }
        val w = 512; val h = 512
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#1D1D26"))
        val arrow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#3D7BFF")
            textSize = 260f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText(if (label.contains("Next")) "\u203A" else "\u2039", w / 2f, h * 0.62f, arrow)
        val name = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 54f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText(label, w / 2f, h * 0.88f, name)
        cache.put(key, bmp)
        return bmp
    }

    /** Large grid tiles are ~600px on a 1280-wide head unit; 512 source keeps them sharp. */
    private fun scale(src: Bitmap): Bitmap {
        val target = 512
        if (src.width <= target) return src
        val h = (src.height.toFloat() * target / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, target, h, true)
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

/** GridTemplate needs Car API 6; older head units keep the plain lists. */
private fun apiAtLeast6(ctx: CarContext): Boolean =
    runCatching { ctx.carAppApiLevel >= 6 }.getOrDefault(false)

/** Load a series' episodes off the main thread, then slide the list in. */
private fun pushEpisodes(
    ctx: CarContext,
    screenManager: androidx.car.app.ScreenManager,
    play: (String, String) -> Unit,
    openPlayer: (Screen, String) -> Unit,
    series: Entry,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch {
        val eps = repoOf(ctx).episodesFor(series)
        scope.cancel()
        screenManager.push(CarEpisodesScreen(ctx, play, openPlayer, series, eps))
    }
}

/**
 * Grid tiles give the title one narrow line, so the label is trimmed: bracketed
 * tags ([FHD], (2021), {4K}) are dropped and anything longer than [CAR_TITLE_MAX]
 * characters is ellipsised. The full name is still what plays — only the label changes.
 */
private fun shortTitle(name: String): String {
    var t = name.replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\{[^}]*\\}"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (t.isBlank()) t = name.trim()
    return if (t.length <= CAR_TITLE_MAX) t else t.take(CAR_TITLE_MAX - 1).trimEnd() + "…"
}

private const val CAR_TITLE_MAX = 22


/**
 * Library search for the car (Movies / TV Shows).
 *
 * Uses AA's own [SearchTemplate], which is the only way to get text on a head unit: the
 * host supplies its keyboard and dictation. Results stream into the template's item list as
 * the query changes — tapping a title plays it (movie) or opens its episodes (series) — and a
 * "See all" row hands the full match set to the poster grid.
 */
class CarLibrarySearchScreen(
    private val ctx: CarContext,
    private val play: (url: String, title: String) -> Unit,
    private val openPlayer: (host: Screen, title: String) -> Unit,
    private val movies: Boolean,
) : Screen(ctx) {

    private var query = ""

    private val callback = object : androidx.car.app.model.SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) {
            query = searchText
            invalidate()
        }

        override fun onSearchSubmitted(searchText: String) {
            query = searchText
            invalidate()
        }
    }

    private fun matches(): List<Entry> {
        val pl = repoOf(ctx).activePlaylist ?: return emptyList()
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val hidden = repoOf(ctx).hiddenGroups(pl.name)
        val source = if (movies) pl.movies else pl.series
        return source.asSequence()
            .filterNot { it.group in hidden }
            .filter { it.name.contains(q, ignoreCase = true) }
            .take(400)
            .toList()
    }

    override fun onGetTemplate(): Template {
        val hits = matches()
        val list = ItemList.Builder()
        when {
            query.trim().length < 2 -> list.addItem(
                Row.Builder()
                    .setTitle(if (movies) "Search movies" else "Search TV shows")
                    .addText("Type a name, or use the microphone")
                    .build())

            hits.isEmpty() -> list.addItem(
                Row.Builder().setTitle("No matches for \u201C${query.trim()}\u201D").build())

            else -> {
                hits.take(5).forEach { e ->
                    list.addItem(
                        Row.Builder()
                            .setTitle(shortTitle(e.name))
                            .addText(if (movies) "Play" else "Open episodes")
                            .setOnClickListener { pick(e) }
                            .build())
                }
                if (hits.size > 5) {
                    list.addItem(
                        Row.Builder()
                            .setTitle("See all ${hits.size} results")
                            .setOnClickListener {
                                screenManager.push(
                                    CarPosterGridScreen(
                                        ctx,
                                        if (movies) "Movies \u2014 \u201C${query.trim()}\u201D"
                                        else "TV Shows \u2014 \u201C${query.trim()}\u201D",
                                        hits
                                    ) { e -> pick(e) })
                            }
                            .build())
                }
            }
        }

        return androidx.car.app.model.SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(query)
            .setSearchHint(if (movies) "Search movies" else "Search TV shows")
            .setShowKeyboardByDefault(false)
            .setItemList(list.build())
            .build()
    }

    private fun pick(e: Entry) {
        if (movies) {
            if (e.url.isNotBlank()) {
                play(e.url, e.name)
                openPlayer(this, e.name)
            }
        } else {
            pushEpisodes(ctx, screenManager, play, openPlayer, e)
        }
    }
}

/** Simple magnifier for the action strip (the library has no search UI of its own). */
private fun searchIcon(): CarIcon {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = size * 0.10f
    }
    c.drawCircle(size * 0.42f, size * 0.42f, size * 0.26f, p)
    c.drawLine(size * 0.62f, size * 0.62f, size * 0.86f, size * 0.86f, p)
    return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
}

private const val MAX_ROWS = 60

/** Grid tiles are heavier than rows; the host paginates anyway. */
/** Posters per car page; the host draws one page at a time. */
private const val PAGE_SIZE = 24
