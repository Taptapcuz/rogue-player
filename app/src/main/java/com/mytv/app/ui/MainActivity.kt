package com.mytv.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytv.app.MyApplication
import com.mytv.app.R
import com.mytv.app.data.Channel
import com.mytv.app.data.Entry
import com.mytv.app.data.Playlist
import com.mytv.app.data.PlaylistKind
import com.mytv.app.data.PlaylistParser
import com.mytv.app.player.PlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * MYTV phone UI — blue-on-black IMPlayer-style clone.
 * Header (logo, search, sort) + filter chips + content, and a floating
 * bottom nav: Home · Live TV · Movies · TV Shows · a 5th (Playlists/Sports).
 *
 * The car side (unchanged) renders the live list via an Android Auto
 * PlaceListNavigationTemplate and projects the player Fermata-style.
 */
class MainActivity : AppCompatActivity() {

    private val app by lazy { application as MyApplication }
    private lateinit var adapter: BrowseAdapter
    private lateinit var contentPane: FrameLayout
    private lateinit var tvPlaceholder: TextView
    private var navRow: LinearLayout? = null
    private var navParams: FrameLayout.LayoutParams? = null
    private var navHidden = false
    private var listView: RecyclerView? = null
    private var chipsRow: LinearLayout? = null

    /** A–Z / Z–A ordering for every list; toggled by the header button. */
    private enum class SortMode(val label: String) { AZ("A–Z"), ZA("Z–A") }
    private var sortMode = SortMode.AZ
    private var sortBtn: TextView? = null

    /** Background page builder (single thread so the sorted-list cache stays consistent). */
    private val pageDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mytv-page").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private var reloadJob: Job? = null
    private var reloadGen = 0

    /** Memoised alphabetically sorted source lists (playlist + sort mode). */
    private var sortedCacheKey: String? = null
    private var sortedCache: Triple<List<Channel>, List<Entry>, List<Entry>>? = null

    /** Current category filter (null = All channels). */
    private var selectedGroup: String? = null

    /** Back button in the header; shown when there's navigation to undo. */
    private var backBtn: TextView? = null

    /** Stack of {tab, filter} states to pop on Back. */
    private val backStack = ArrayDeque<Pair<Tab, String?>>()

    private var tab = Tab.LIVE

    /** Sentinel filter for the starred channels; provider groups never start with ★. */
    private val FAV_FILTER = "★ Favorites"

    /** Favourited channel ids — shared with the EPG guide through this prefs file. */
    private val favPrefs by lazy { getSharedPreferences("mytv_favorites", MODE_PRIVATE) }
    private val favorites = mutableSetOf<String>()

    /** Last list position per (tab, filter) so coming back doesn't jump to the top. */
    private val listPositions = mutableMapOf<String, Int>()
    private fun positionKey(): String = tab.name + "|" + (selectedGroup ?: "")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val onClickChannel: (Channel) -> Unit = { ch -> startPlayback(ch.url, ch.name, 0) }
    private val onClickEntry: (Entry, Boolean) -> Unit = { e, isMovie ->
        // A poster tap opens the detail page (art, plot, cast, trailer, Play) instead
        // of jumping straight into the stream. Series pick their episode there too —
        // Xtream series have no single stream of their own.
        startActivity(DetailActivity.intent(this, e, isMovie))
    }
    private val onClickEpisode: (com.mytv.app.data.Episode, String) -> Unit = { ep, seriesTitle ->
        startPlayback(ep.url, "${Titles.clean(seriesTitle)} · ${ep.title}".trim(' ', '·'), 0)
    }

    enum class Tab(val short: String, val label: String, @androidx.annotation.DrawableRes val iconRes: Int) {
        HOME("Home", "Home", R.drawable.ic_nav_home),
        LIVE("Live TV", "Live TV", R.drawable.ic_nav_live),
        MOVIES("Movies", "Movies", R.drawable.ic_nav_movies),
        SHOWS("TV Shows", "TV Shows", R.drawable.ic_nav_tv),
        PLAYLISTS("Playlists", "Playlists", R.drawable.ic_nav_playlists),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        loadFavorites()
        installBackHandler()
        buildChrome()
    }

    /**
     * The EPG guide writes the same favourites prefs file, so pick up anything it
     * changed while we were in the background and repaint the star badges.
     */
    override fun onResume() {
        super.onResume()
        val before = favorites.toSet()
        loadFavorites()
        if (favorites != before) {
            adapter.favoriteIds = favorites.toSet()
            if (selectedGroup == FAV_FILTER && tab == Tab.LIVE) reload()
            else adapter.notifyDataSetChanged()
        }
    }

    /** Read the shared favourites set. */
    private fun loadFavorites() {
        favorites.clear()
        favorites.addAll(favPrefs.getStringSet("ids", emptySet()).orEmpty())
    }

    /**
     * Long-press a channel card: star / unstar it. Read-modify-write against prefs
     * so the guide and this screen can't clobber each other's stars.
     */
    private fun toggleFavorite(ch: Channel) {
        val stored = favPrefs.getStringSet("ids", emptySet()).orEmpty().toMutableSet()
        val added = if (ch.id in stored) {
            stored.remove(ch.id)
            false
        } else {
            stored.add(ch.id)
            true
        }
        favPrefs.edit().putStringSet("ids", stored).commit()
        favorites.clear()
        favorites.addAll(stored)
        adapter.favoriteIds = favorites.toSet()
        // Rebuild (not just repaint) so the Favorites filter drops a removed row.
        if (tab == Tab.LIVE) reload() else adapter.notifyDataSetChanged()
        android.widget.Toast.makeText(
            this,
            if (added) "★ Added to Favorites — ${ch.name}" else "Removed from Favorites — ${ch.name}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    // --------------------------------------------------------- back ---

    /**
     * Back is routed through the dispatcher (not the deprecated
     * onBackPressed override): with predictive back on by default at
     * targetSdk 36 the framework ignores the old override and would just
     * finish the activity.
     */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
    }

    /**
     * Push the current {tab, filter} onto the back stack (called before any
     * drill-in navigation), then update the back button visibility.
     */
    private fun pushState() {
        backStack.addLast(tab to selectedGroup)
        // Keep the stack shallow so a long navigation run can't grow unbounded.
        if (backStack.size > 20) backStack.removeFirst()
        updateBackButton()
    }

    /** Pop one navigation level, or finish the activity if nothing to undo. */
    private fun goBack() {
        val prev = backStack.removeLastOrNull()
        if (prev == null) {
            finish()
            return
        }
        val (prevTab, prevFilter) = prev
        selectedGroup = prevFilter
        tab = prevTab
        renderContent()
        renderNav()
        updateBackButton()
    }

    /** Show the back arrow only when there's a navigation to undo. */
    private fun updateBackButton() {
        backBtn?.visibility =
            if (backStack.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------- chrome ---

    @SuppressLint("SetTextI18n")
    private fun buildChrome() {
        backBtn = TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Palette.MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        root.addView(header())

        contentPane = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        root.addView(contentPane, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        // Keep the header clear of the status bar (it draws edge-to-edge).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, top, 0, 0)
            insets
        }
        renderContent()

        // floating bottom nav over content
        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Pad so the first/last items aren't clipped by the rounded caps.
            setPadding(dp(6), 0, dp(6), 0)
            setBackground(rounded(Palette.PANEL, 20))
            elevation = 8f
        }
        navParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(10), 0, dp(10), dp(12))
        }
        renderNav()
        attachNavRow()
    }

    private fun header(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(4))
        }
        h.addView(backBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(0, 0, dp(2), 0)
        })
        h.addView(logo("M", 32))
        h.addView(TextView(this).apply {
            text = "MYTV"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
        })
        val tv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tv.addView(TextView(this).apply {
            text = "mobile"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setBackground(rounded(Palette.ACCENTS[0], 6))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(46), dp(16)))
        h.addView(tv)
        h.addView(TextView(this).apply { // spacer
            text = "1.0"
            textSize = 10f
            setTextColor(Palette.DIM)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
            setMargins(dp(10), 0, 0, 0)
        })
        h.addView(TextView(this).apply {
            text = "⌕"
            textSize = 18f
            setTextColor(Palette.MUTED)
            setOnClickListener { showSearch() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER_VERTICAL })
        sortBtn = TextView(this).apply {
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
            setBackground(rounded(Palette.CARD, 9))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            gravity = Gravity.CENTER
            setOnClickListener { toggleSort() }
        }.also { renderSortLabel(it) }
        h.addView(sortBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(2)
        })
        h.addView(logo("i", 28))
        return h
    }

    private fun filterChips(): View {
        val scroll = HorizontalScrollView(this)
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        scroll.addView(chips)
        chipsRow = chips
        // Filled from the background page build in reload() — deriving the
        // category list walks every playlist entry, which is too slow for the
        // main thread on a 50k-entry provider.
        renderChips(chips, emptyList())
        return scroll
    }

    /** One rendered page: the row items plus the chip definitions behind them. */
    private data class Page(
        val items: List<BrowseItem>,
        val chips: List<Pair<String, Boolean>>,
    )

    /** Category chips for the current tab, honoring [selectedGroup]. */
    private fun buildChipDefs(
        tab: Tab,
        selectedGroup: String?,
        pl: Playlist?,
    ): List<Pair<String, Boolean>> = when (tab) {
        Tab.HOME -> listOf(
            "All Sections" to true,
            "Live TV" to false,
            "Movies" to false,
            "TV Shows" to false,
        )
        Tab.LIVE -> listOf(
            FAV_FILTER to (selectedGroup == FAV_FILTER),
            "All Channels" to (selectedGroup == null),
        ) +
            (pl?.channels?.map { it.group }?.distinct()?.filter { it.isNotBlank() }?.sorted() ?: emptyList())
                .map { g -> g to (g == selectedGroup) }
        Tab.MOVIES -> listOf("All Movies" to (selectedGroup == null)) +
            (pl?.movies?.map { it.group }?.distinct()?.filter { it.isNotBlank() }?.sorted() ?: emptyList())
                .map { g -> g to (g == selectedGroup) }
        Tab.SHOWS -> listOf("All TV Shows" to (selectedGroup == null)) +
            (pl?.series?.map { it.group }?.distinct()?.filter { it.isNotBlank() }?.sorted() ?: emptyList())
                .map { g -> g to (g == selectedGroup) }
        else -> emptyList()
    }

    /** (Re)build the chip rows from [defs]; "All" is the first label. */
    private fun renderChips(container: LinearLayout, defs: List<Pair<String, Boolean>>) {
        container.removeAllViews()
        defs.forEach { (label, active) ->
            val isAll = label == "All Channels" || label == "All Movies" ||
                label == "All TV Shows" || label == "All Sections"
            container.addView(chip(label, active, isAll), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun chip(label: String, active: Boolean, isAll: Boolean): View = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(if (active) Color.WHITE else Palette.MUTED)
        setBackground(rounded(if (active) Palette.ACCENTS[0] else Palette.CARD, 18))
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener {
            if (tab == Tab.HOME) {
                // Section chips act as fast tab-jumpers on the Home screen.
                val target = when (label) {
                    "Live TV" -> Tab.LIVE
                    "Movies" -> Tab.MOVIES
                    "TV Shows" -> Tab.SHOWS
                    else -> null  // "All Sections"
                }
                if (target != null) {
                    pushState()
                    selectedGroup = null
                    tab = target
                    renderContent(); renderNav()
                    return@setOnClickListener
                }
            }
            if (!isAll) pushState()
            selectedGroup = if (isAll) null else label
            // reload() rebuilds both the list and the chip highlights off the
            // main thread, so the tap never blocks on a big playlist.
            reload()
        }
    }

    // ------------------------------------------------------------- nav ---

    private fun renderNav() {
        val row = navRow ?: return
        row.removeAllViews()
        Tab.entries.forEach { t ->
            val active = t == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (t != tab) pushState()  // back returns to the prior tab
                    selectedGroup = null  // fresh filter per tab
                    tab = t; renderContent(); renderNav()
                }
            }

            // Long-press Live TV to pick the view: channel list or EPG guide.
            if (t == Tab.LIVE) {
                item.isLongClickable = true
                item.setOnLongClickListener { anchor ->
                    showLiveViewMenu(anchor)
                    true
                }
            }
            // Real vector icons instead of font glyphs: the "▣" used for TV Shows
            // rendered as a square with a solid dot inside, which read as a stray
            // white dot in the bar.
            item.addView(android.widget.ImageView(this).apply {
                setImageResource(t.iconRes)
                setColorFilter(if (active) Palette.ACCENTS[0] else Palette.MUTED)
                contentDescription = t.label
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })

            item.addView(TextView(this).apply {
                text = t.label
                textSize = 10f
                gravity = Gravity.CENTER
                includeFontPadding = false
                isSingleLine = true
                setTextColor(if (active) Palette.ACCENTS[0] else Palette.MUTED)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))

            row.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /**
     * Long-press on Live TV: pick between the plain channel list and the full
     * TiViMate-style EPG guide. (A dialog rather than a PopupMenu: the popup
     * anchored to a bottom-bar item is dismissed by the finger release that ends
     * the long press.)
     */
    private fun showLiveViewMenu(anchor: View) {
        val options = arrayOf("Live channel list", "TV guide (EPG)")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Live TV view")
            .setItems(options) { _, which ->
                when (which) {
                    1 -> startActivity(Intent(this, GuideActivity::class.java))
                    else -> {
                        if (tab != Tab.LIVE) {
                            pushState()
                            tab = Tab.LIVE
                        }
                        selectedGroup = null
                        renderContent()
                        renderNav()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- body ---

    private fun renderContent() {
        contentPane.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentPane.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Playlists get their own manager screen (list + update/delete) instead of
        // jumping straight into the add dialog like before.
        if (tab == Tab.PLAYLISTS) {
            renderPlaylists(col)
            return
        }

        col.addView(filterChips())

        // Movies/Shows render as an adaptive Plex-style poster grid; the live
        // list stays single-column.
        val grid = tab == Tab.MOVIES || tab == Tab.SHOWS
        val span = if (grid) gridSpanCount() else 1

        adapter = BrowseAdapter(
            onClickChannel, onClickEntry,
            onEpisode = onClickEpisode,
            onChannelLong = { ch -> toggleFavorite(ch) },
        ).apply { favoriteIds = favorites.toSet() }
        val list = RecyclerView(this).apply {
            clipToPadding = false
            // Bottom padding keeps the last row clear of the floating nav bar.
            setPadding(dp(10), dp(6), dp(10), dp(88))
        }
        val lm = GridLayoutManager(this, span)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val item = adapter.currentList.getOrNull(position)
                return if (item is BrowseItem.SectionTitle || item is BrowseItem.Message) span else 1
            }
        }
        list.layoutManager = lm
        list.adapter = adapter
        col.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        listView = list
        listPositions[positionKey()]?.takeIf { it > 0 }?.let { pos ->
            list.layoutManager?.scrollToPosition(pos)
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    // Out of the way while the user is moving the list…
                    RecyclerView.SCROLL_STATE_DRAGGING,
                    RecyclerView.SCROLL_STATE_SETTLING -> hideNav()

                    // …back once the list comes to rest.
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        showNav()
                        loadEpgForVisible()
                        (recyclerView.layoutManager as? LinearLayoutManager)?.let {
                            listPositions[positionKey()] = it.findFirstVisibleItemPosition()
                        }
                    }
                }
            }
        })

        tvPlaceholder = TextView(this).apply {
            setTextColor(Palette.MUTED); textSize = 14f
            gravity = Gravity.CENTER; visibility = View.GONE
        }
        col.addView(tvPlaceholder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))

        attachNavRow()   // content rebuild wipes the nav overlay; restore it
        reload()
    }

    /** (Re)attach the floating nav bar to the content pane. */
    private fun attachNavRow() {
        val row = navRow ?: return
        val params = navParams ?: return
        if (row.parent == null) contentPane.addView(row, params)
        // A rebuild starts a fresh page at the top: always begin visible.
        row.translationY = 0f
        row.alpha = 1f
        navHidden = false
    }

    /** Slide the bottom nav out of the way while the list is being scrolled. */
    private fun hideNav() {
        val row = navRow ?: return
        if (navHidden) return
        navHidden = true
        val barHeight = row.height.takeIf { it > 0 } ?: dp(64)
        android.util.Log.d("MYTVNav", "hide (bar ${barHeight}px)")
        row.animate().cancel()
        row.animate()
            .translationY((barHeight + dp(20)).toFloat())
            .alpha(0f)
            .setDuration(180)
            .start()
    }

    /** Bring it back when the list stops moving. */
    private fun showNav() {
        val row = navRow ?: return
        if (!navHidden) return
        navHidden = false
        android.util.Log.d("MYTVNav", "show")
        row.animate().cancel()
        row.animate().translationY(0f).alpha(1f).setDuration(180).start()
    }

    private fun reload() {
        val pl = app.repository.activePlaylist
        if (pl == null) {
            adapter.submitList(emptyList())
            tvPlaceholder.visibility = View.VISIBLE
            tvPlaceholder.text = "No playlist yet.\nTap “Playlists” (bottom right) for an M3U link or Xtream login."
            return
        }

        // The Playlists manager draws itself; there is no list page to build.
        if (tab == Tab.PLAYLISTS) return

        tvPlaceholder.visibility = View.GONE

        // Building a page means sorting/filtering the whole playlist — hundreds of
        // thousands of entries on a big provider. Doing that on the UI thread is
        // what made taps time out (ANR "Input dispatching timed out" in
        // MainActivity). Build it on a single background thread, and drop the
        // result if another reload started meanwhile.
        val gen = ++reloadGen
        val tabNow = tab
        val filterNow = selectedGroup
        val sortNow = sortMode
        val favNow = favorites.toSet()
        reloadJob?.cancel()
        reloadJob = scope.launch {
            val page = withContext(pageDispatcher) {
                buildPage(pl, tabNow, filterNow, sortNow, favNow)
            }
            if (gen != reloadGen) return@launch
            chipsRow?.let { row -> renderChips(row, page.chips) }
            adapter.submitList(page.items)
            loadEpgForVisible()
        }
    }

    /** Build the item list + chips for [tab]/[filter] entirely off the main thread. */
    private fun buildPage(
        pl: Playlist,
        tab: Tab,
        filter: String?,
        mode: SortMode,
        favorites: Set<String>,
    ): Page {
        val (channels, movies, shows) = sortedSources(pl, mode)

        fun <T> filterBy(rows: List<T>, group: (T) -> String): List<T> =
            filter?.let { g -> rows.filter { group(it) == g } } ?: rows

        val chans = if (filter == FAV_FILTER) {
            channels.filter { it.id in favorites }
        } else {
            filterBy(channels) { it.group }
        }
        val movs = filterBy(movies) { it.group }
        val shws = filterBy(shows) { it.group }

        val items = when (tab) {
            Tab.HOME -> listOf(
                BrowseItem.SectionTitle("Top Channels",
                    "${Titles.count(channels.size)} channels") as BrowseItem) +
                channels.take(12).map { BrowseItem.ChannelCard(it) as BrowseItem }
            Tab.LIVE -> listOf(
                BrowseItem.SectionTitle(filter ?: "All Channels",
                    if (filter == FAV_FILTER) {
                        "${Titles.count(chans.size)} favourites"
                    } else {
                        "${Titles.count(chans.size)} channels"
                    }) as BrowseItem) +
                chans.map { BrowseItem.ChannelCard(it) as BrowseItem }
            Tab.MOVIES -> listOf(
                BrowseItem.SectionTitle(filter ?: "All Movies",
                    Titles.count(movs.size)) as BrowseItem) +
                movs.map { BrowseItem.PosterCard(it, true) as BrowseItem }
            Tab.SHOWS -> listOf(
                BrowseItem.SectionTitle(filter ?: "All TV Shows",
                    Titles.count(shws.size)) as BrowseItem) +
                shws.map { BrowseItem.PosterCard(it, false) as BrowseItem }
            else -> channels.map { BrowseItem.ChannelCard(it) as BrowseItem }
        }
        return Page(items, buildChipDefs(tab, filter, pl))
    }

    /**
     * Alphabetically sorted channel/movie/series lists, memoised per playlist and
     * sort direction: sorting 50k titles with the prefix-stripping key costs
     * hundreds of ms, and it would otherwise run on every tab/filter tap.
     */
    private fun sortedSources(
        pl: Playlist,
        mode: SortMode,
    ): Triple<List<Channel>, List<Entry>, List<Entry>> {
        val key = "${pl.name}|${pl.url}|${mode.name}|" +
            "${pl.channels.size}/${pl.movies.size}/${pl.series.size}"
        sortedCacheKey?.let { k ->
            if (k == key) sortedCache?.let { return it }
        }
        val result = Triple(
            sortRows(pl.channels, mode) { it.name },
            sortRows(pl.movies, mode) { it.name },
            sortRows(pl.series, mode) { it.name },
        )
        sortedCacheKey = key
        sortedCache = result
        return result
    }

    /** Sort rows by [mode] using the display title as the key. */
    private fun <T> sortRows(rows: List<T>, mode: SortMode, name: (T) -> String): List<T> {
        val asc = rows.sortedBy { Titles.sortKey(name(it)) }
        return if (mode == SortMode.ZA) asc.asReversed() else asc
    }

    private fun toggleSort() {
        sortMode = if (sortMode == SortMode.AZ) SortMode.ZA else SortMode.AZ
        sortBtn?.let { renderSortLabel(it) }
        reload()
        listView?.scrollToPosition(0)
    }

    private fun renderSortLabel(tv: TextView) {
        tv.text = "⇅ ${sortMode.label}"
    }

    /** Columns that keep poster tiles near 105dp wide on this screen. */
    private fun gridSpanCount(): Int {
        val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (wDp / 108f).toInt().coerceIn(2, 6)
    }

    // ------------------------------------------------------------- EPG ---

    /** Avoid re-fetching EPG for channels already loaded in this session. */
    private val epgFetched = mutableSetOf<String>()
    private var epgJob: Job? = null

    /** Load EPG for the currently visible channel rows (bounded, on-demand). */
    private fun loadEpgForVisible() {
        val rv = listView ?: return
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        if (tab != Tab.LIVE && tab != Tab.HOME) return
        if (app.repository.activePlaylist?.kind != PlaylistKind.XTREAM) return

        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return

        // Collect channel id → Channel for the visible window.
        val visible = mutableListOf<Channel>()
        for (pos in first..last) {
            if (pos >= adapter.currentList.size) continue
            val card = adapter.currentList[pos] as? BrowseItem.ChannelCard ?: continue
            if (card.ch.id in epgFetched) continue
            epgFetched.add(card.ch.id)
            visible.add(card.ch)
        }
        if (visible.isEmpty()) return

        epgJob?.cancel()
        epgJob = scope.launch {
            // Fetch sequentially so we don't hammer the provider all at once.
            for (ch in visible) {
                launch {
                    runCatching {
                        val now = app.repository.nowPlaying(ch)
                        val next = app.repository.nextPlaying(ch)
                        adapter.updateEpg(ch, now, next)
                    }
                }
            }
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun startPlayback(url: String, title: String, posMs: Long) {
        if (url.isBlank()) { toast("No stream URL"); return }
        // Start the background service (keeps audio playing + powers the
        // media-session controls, and the car projection reuses this player).
        startService(Intent(this, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY)
            .putExtra(PlaybackService.EXTRA_URL, url)
            .putExtra(PlaybackService.EXTRA_TITLE, title)
            .putExtra(PlaybackService.EXTRA_POSITION, posMs))
        // Open the fullscreen player screen with state overlays.
        startActivity(PlayerActivity.intent(this, url, title))
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ----------------------------------------------------------- search ---

    /** Full-screen search overlay: query field + live-filtered results. */
    private fun showSearch() {
        val overlay = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val host = window.decorView as FrameLayout
        host.addView(overlay, params)

        val q = EditText(this).apply {
            hint = "Search channels, movies, shows…"
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.DIM)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.CARD, 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(46)
            setSingleLine(true)
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Palette.MUTED)
            gravity = Gravity.CENTER
            setBackground(rounded(Palette.CARD, 12))
            setOnClickListener { host.removeView(overlay) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        top.addView(q, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, dp(8), 0)
        })
        top.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))

        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity) }
        val results = BrowseAdapter(
            onChannel = { ch -> host.removeView(overlay); onClickChannel(ch) },
            onEntry = { e, isMovie -> host.removeView(overlay); onClickEntry(e, isMovie) },
            compactPosters = true,
        )
        list.adapter = results

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(top)
        col.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // The overlay is added straight to the decor view, so it drew under the
        // status bar and the search field was half hidden at the very top.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(col) { v, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, topInset, v.paddingRight, v.paddingBottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(col)

        // Filtering runs off the UI thread: a 50k-entry playlist would otherwise
        // stutter on every keystroke (same class of problem as the old ANRs).
        var queryJob: Job? = null
        fun runQuery() {
            val needle = q.text.toString().trim()
            val pl = app.repository.activePlaylist
            if (pl == null || needle.isEmpty()) {
                queryJob?.cancel()
                results.submitList(emptyList())
                return
            }
            queryJob?.cancel()
            queryJob = scope.launch {
                val items = withContext(pageDispatcher) { searchItems(pl, needle) }
                if (q.text.toString().trim() != needle) return@launch
                results.submitList(items)
            }
        }

        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = runQuery()
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        q.requestFocus()
    }

    /**
     * Filter channels/movies/shows for [needle]. Pure and thread-safe — called on
     * the background page dispatcher so typing never blocks the UI thread.
     */
    private fun searchItems(pl: Playlist, needle: String): List<BrowseItem> {
        val n = needle.lowercase()
        fun hit(name: String, group: String) =
            name.lowercase().contains(n) || group.lowercase().contains(n)

        val channels = pl.channels.filter { hit(it.name, it.group) }
        val movies = pl.movies.filter { hit(it.name, it.group) }
        val series = pl.series.filter { hit(it.name, it.group) }

        val items = mutableListOf<BrowseItem>()
        if (channels.isNotEmpty()) {
            items.add(BrowseItem.SectionTitle("Channels", "${channels.size}"))
            channels.forEach { items.add(BrowseItem.ChannelCard(it)) }
        }
        if (movies.isNotEmpty()) {
            items.add(BrowseItem.SectionTitle("Movies", "${movies.size}"))
            movies.forEach { items.add(BrowseItem.PosterCard(it, true)) }
        }
        if (series.isNotEmpty()) {
            items.add(BrowseItem.SectionTitle("TV Shows", "${series.size}"))
            series.forEach { items.add(BrowseItem.PosterCard(it, false)) }
        }
        if (items.isEmpty()) items.add(BrowseItem.Message("No matches"))
        return items
    }

    // --------------------------------------------------------- playlists ---

    /**
     * The Playlists screen: one card per saved source with Update / Delete, plus
     * an Add button. Tapping a card makes it the active source.
     */
    private fun renderPlaylists(col: LinearLayout) {
        val playlists = app.repository.playlists.value
        val activeId = app.repository.activePlaylistId.value

        val add = TextView(this).apply {
            text = "＋  Add playlist"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackground(rounded(Palette.ACCENTS[0], 10))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { showAddDialog() }
        }
        col.addView(add, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(4), dp(12), dp(8))
        })

        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(96))
        }
        scroll.addView(stack, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        col.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (playlists.isEmpty()) {
            stack.addView(TextView(this).apply {
                text = "No playlists yet.\nTap “Add playlist” for an M3U link or an Xtream login."
                setTextColor(Palette.MUTED)
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(48), dp(18), dp(48))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return
        }

        playlists.forEach { pl ->
            stack.addView(playlistCard(pl, pl.name == activeId), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    /** One saved playlist: tap to activate, Update to re-fetch, Delete to remove. */
    private fun playlistCard(pl: Playlist, active: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackground(rounded(if (active) Palette.CARD_HI else Palette.CARD, 12))
            isClickable = true
            setOnClickListener {
                if (active) return@setOnClickListener
                app.repository.setActive(pl.name)
                selectedGroup = null
                toast("Active: ${pl.name}")
                renderContent()
                reload()
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = pl.name
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (active) {
            titleRow.addView(TextView(this).apply {
                text = "ACTIVE"
                textSize = 9.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                setBackground(rounded(Palette.ACCENTS[0], 8))
                setPadding(dp(7), dp(3), dp(7), dp(3))
            })
        }
        card.addView(titleRow)

        card.addView(TextView(this).apply {
            text = "${if (pl.kind == PlaylistKind.XTREAM) "Xtream" else "M3U"} · " +
                "${pl.channels.size} channels · ${pl.movies.size} movies · ${pl.series.size} shows"
            textSize = 11.5f
            setTextColor(Palette.MUTED)
            maxLines = 1
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(cardAction("⟳  Update") { updatePlaylist(pl) })
        actions.addView(cardAction("✏  Edit") { editPlaylist(pl) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            })
        actions.addView(cardAction("🗑  Delete", Palette.CARD_HI) { confirmDelete(pl) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            })
        card.addView(actions)

        return card
    }

    private fun cardAction(label: String, accent: Int = Palette.CARD_HI, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
            setBackground(rounded(accent, 9))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Re-fetch a stored playlist from the provider, in place. */
    private fun updatePlaylist(pl: Playlist) {
        toast("Updating ${pl.name}…")
        scope.launch {
            // Stored Xtream sources keep the full player_api URL; the parser wants
            // the bare host. Hoisted so the failure toast can name the address tried.
            val target = if (pl.kind == PlaylistKind.XTREAM) {
                pl.url.substringBefore("/player_api.php")
            } else {
                pl.url
            }
            val result = runCatching {
                PlaylistParser.load(pl.kind, target, pl.user, pl.pass)
            }
            result.onSuccess { fresh ->
                // A flaky panel can answer with a valid-looking but empty listing;
                // never let that overwrite a working channel list.
                if (fresh.channels.isEmpty() && fresh.movies.isEmpty() && fresh.series.isEmpty()) {
                    toast("Update failed — provider returned no channels (kept your list)")
                    return@onSuccess
                }
                app.repository.replaceContents(pl.name, fresh)
                renderContent()
                reload()
                toast("Updated ${pl.name} — ${fresh.channels.size} channels")
            }.onFailure { t ->
                Log.e("MYTV", "playlist update failed", t)
                // Distinguish "server is dead" from "credentials rejected": an
                // IOException here means the panel dropped the connection, which a
                // user can't fix by retyping their password.
                val msg = when {
                    t is java.io.IOException -> "Provider unreachable — the server dropped the connection"
                    t.message?.contains("end of stream", true) == true ||
                        t.message?.contains("reset", true) == true ->
                        "Provider unreachable — the server dropped the connection"
                    else -> t.message ?: "update failed"
                }
                toast("Update failed: $msg — tried $target")
            }
        }
    }

    /**
     * Edit a saved source in place: fix the host/port or the credentials after a
     * provider moves the panel, keeping its name (so favourites and the active
     * source stay attached), then refresh it.
     */
    private fun editPlaylist(pl: Playlist) {
        val fields = LayoutInflater.from(this).inflate(R.layout.dialog_add_playlist, null)
        val urlEdit = fields.findViewById<EditText>(R.id.edit_url)
        val hostEdit = fields.findViewById<EditText>(R.id.edit_host)
        val userEdit = fields.findViewById<EditText>(R.id.edit_user)
        val passEdit = fields.findViewById<EditText>(R.id.edit_pass)
        val nameEdit = fields.findViewById<EditText>(R.id.edit_name)
        val tabM3u = fields.findViewById<TextView>(R.id.tab_m3u)
        val tabXtream = fields.findViewById<TextView>(R.id.tab_xtream)

        var isXtream = pl.kind == PlaylistKind.XTREAM
        fun syncTabs() {
            urlEdit.visibility = if (isXtream) View.GONE else View.VISIBLE
            val xVis = if (isXtream) View.VISIBLE else View.GONE
            hostEdit.visibility = xVis; userEdit.visibility = xVis; passEdit.visibility = xVis
            tabM3u.setBackgroundColor(if (!isXtream) Palette.CARD_HI else Palette.CARD)
            tabM3u.setTextColor(if (!isXtream) Palette.TEXT else Palette.MUTED)
            tabXtream.setBackgroundColor(if (isXtream) Palette.CARD_HI else Palette.CARD)
            tabXtream.setTextColor(if (isXtream) Palette.TEXT else Palette.MUTED)
        }
        tabM3u.setOnClickListener { isXtream = false; syncTabs() }
        tabXtream.setOnClickListener { isXtream = true; syncTabs() }

        nameEdit.setText(pl.name)
        // Stored Xtream sources keep the full player_api URL; the field wants the host.
        hostEdit.setText(
            if (pl.kind == PlaylistKind.XTREAM) pl.url.substringBefore("/player_api.php") else ""
        )
        urlEdit.setText(if (pl.kind == PlaylistKind.M3U) pl.url else "")
        userEdit.setText(pl.user.orEmpty())
        passEdit.setText(pl.pass.orEmpty())
        syncTabs()

        AlertDialog.Builder(this)
            .setTitle("Edit playlist")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val target = if (isXtream) hostEdit.text.toString().trim()
                    else urlEdit.text.toString().trim()
                    if (target.isBlank()) {
                        toast("Enter the host or URL")
                        return@setOnClickListener
                    }
                    scope.launch {
                        val result = runCatching {
                            PlaylistParser.load(
                                if (isXtream) PlaylistKind.XTREAM else PlaylistKind.M3U,
                                target,
                                userEdit.text.toString().trim().ifBlank { null },
                                passEdit.text.toString().trim().ifBlank { null },
                            )
                        }
                        result.onSuccess { fresh ->
                            if (fresh.channels.isEmpty() && fresh.movies.isEmpty() && fresh.series.isEmpty()) {
                                toast("Save failed — provider returned no channels")
                                return@onSuccess
                            }
                            app.repository.replaceContents(pl.name, fresh)
                            renderContent()
                            reload()
                            dialog.dismiss()
                            toast("Saved — ${fresh.channels.size} channels")
                        }.onFailure { t ->
                            Log.e("MYTV", "edit failed", t)
                            val msg = if (t is java.io.IOException) {
                                "provider unreachable — check the host/port"
                            } else {
                                t.message ?: "failed"
                            }
                            toast("Save failed: $msg — tried $target")
                        }
                    }
                }
            }
    }

    private fun confirmDelete(pl: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("Delete playlist")
            .setMessage("Remove “${pl.name}” from this device? The provider account itself is untouched.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                app.repository.remove(pl.name)
                selectedGroup = null
                renderContent()
                renderNav()
                reload()
                toast("Deleted ${pl.name}")
            }
            .show()
    }

    /** Add-playlist dialog (M3U or Xtream). */
    @SuppressLint("SetTextI18n")
    fun showAddDialog() {
        val fields = LayoutInflater.from(this).inflate(R.layout.dialog_add_playlist, null)
        val urlEdit = fields.findViewById<EditText>(R.id.edit_url)
        val hostEdit = fields.findViewById<EditText>(R.id.edit_host)
        val userEdit = fields.findViewById<EditText>(R.id.edit_user)
        val passEdit = fields.findViewById<EditText>(R.id.edit_pass)
        val nameEdit = fields.findViewById<EditText>(R.id.edit_name)
        val tabM3u = fields.findViewById<TextView>(R.id.tab_m3u)
        val tabXtream = fields.findViewById<TextView>(R.id.tab_xtream)

        var isXtream = false
        fun refresh() {
            urlEdit.visibility = if (isXtream) View.GONE else View.VISIBLE
            val xVis = if (isXtream) View.VISIBLE else View.GONE
            hostEdit.visibility = xVis; userEdit.visibility = xVis; passEdit.visibility = xVis
            tabM3u.setBackgroundColor(if (!isXtream) Palette.CARD_HI else Palette.CARD)
            tabM3u.setTextColor(if (!isXtream) Palette.TEXT else Palette.MUTED)
            tabXtream.setBackgroundColor(if (isXtream) Palette.CARD_HI else Palette.CARD)
            tabXtream.setTextColor(if (isXtream) Palette.TEXT else Palette.MUTED)
        }
        tabM3u.setOnClickListener { isXtream = false; refresh() }
        tabXtream.setOnClickListener { isXtream = true; refresh() }
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Add a playlist")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    scope.launch {
                        try {
                            val name = nameEdit.text.toString().trim()
                            val src = if (isXtream) {
                                PlaylistParser.load(PlaylistKind.XTREAM,
                                    hostEdit.text.toString().trim(),
                                    userEdit.text.toString().trim(),
                                    passEdit.text.toString().trim())
                            } else {
                                PlaylistParser.load(PlaylistKind.M3U,
                                    urlEdit.text.toString().trim(), null, null)
                            }
                            if (src.channels.isEmpty() && src.movies.isEmpty() && src.series.isEmpty()) {
                                // Don't save an empty source: it looks like success and
                                // leaves a dead playlist behind.
                                toast("Login failed — provider returned no channels")
                                return@launch
                            }
                            app.repository.add(if (name.isNotBlank()) src.copy(name = name) else src)
                            renderContent(); renderNav()
                            dialog.dismiss()
                        } catch (t: Throwable) {
                            Log.e("MYTV", "add failed", t)
                            toast("Load failed: ${t.message}")
                        }
                    }
                }
            }
    }

    // -------------------------------------------------------- palette ---

    private fun logo(ch: String, size: Int): View {
        val tile = TextView(this).apply {
            text = ch
            textSize = size * 0.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackground(rounded(Palette.ACCENTS[0], 7))
        }
        tile.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
            marginEnd = dp(8)
        }
        return tile
    }

    private fun rounded(fill: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val MENU_LIVE_LIST = 1
        private const val MENU_GUIDE = 2
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
