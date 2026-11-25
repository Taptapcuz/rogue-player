package com.rogueplayer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rogueplayer.MyApplication
import com.rogueplayer.R
import com.rogueplayer.data.Channel
import com.rogueplayer.data.Entry
import com.rogueplayer.data.Playlist
import com.rogueplayer.data.PlaylistKind
import com.rogueplayer.data.PlaylistParser
import com.rogueplayer.player.PlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
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
    private var globeBtn: TextView? = null
    private var searchBtn: TextView? = null

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

    private var tab = Tab.HOME

    /** Last accent index we rendered, so returning from Settings refreshes the theme. */
    private var shownAccent = -1

    /** Header logo tiles (R / i) + the "player" pill — recolored on accent change. */
    private val accentLogos = mutableListOf<android.widget.TextView>()
    private var playerPill: android.widget.TextView? = null

    /** Sentinel filter for the starred channels; provider groups never start with ★. */
    private val FAV_FILTER = "★ Favorites"

    /** Playlist whose category mask is open (null = the playlist list itself). */
    private var categoryEditor: String? = null

    /** Favourited channel ids — shared with the EPG guide through this prefs file. */
    private val favPrefs by lazy { getSharedPreferences("mytv_favorites", MODE_PRIVATE) }
    private val favorites = mutableSetOf<String>()

    /** Last list position per (tab, filter) so coming back doesn't jump to the top. */
    private val listPositions = mutableMapOf<String, Int>()
    private fun positionKey(): String = tab.name + "|" + (selectedGroup ?: "")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val onClickChannel: (Channel) -> Unit = { ch -> startPlayback(ch.url, ch.name, 0) }

    /** Last score slate, refreshed behind the Sports tab; read while building the page. */
    @Volatile private var scoreGames: List<com.rogueplayer.data.ScoreGame> = emptyList()
    private var scoreFetchAt = 0L
    private val onClickEntry: (Entry, Boolean) -> Unit = { e, isMovie ->
        // A poster tap opens the detail page (art, plot, cast, trailer, Play) instead
        // of jumping straight into the stream. Series pick their episode there too —
        // Xtream series have no single stream of their own.
        startActivity(DetailActivity.intent(this, e, isMovie))
    }
    private val onClickEpisode: (com.rogueplayer.data.Episode, String) -> Unit = { ep, seriesTitle ->
        startPlayback(ep.url, "${Titles.clean(seriesTitle)} · ${ep.title}".trim(' ', '·'), 0)
    }

    enum class Tab(val short: String, val label: String, @androidx.annotation.DrawableRes val iconRes: Int) {
        HOME("Home", "Home", R.drawable.ic_nav_home),
        LIVE("Live TV", "Live TV", R.drawable.ic_nav_live),
        MOVIES("Movies", "Movies", R.drawable.ic_nav_movies),
        SHOWS("TV Shows", "TV Shows", R.drawable.ic_nav_tv),
        SPORTS("Sports", "Sports", R.drawable.ic_nav_sports),
        // Playlists is no longer a bottom-nav tab (Sports took slot 5); it stays
        // in the enum so Settings and the Home landing tile can still open it.
        PLAYLISTS("Playlists", "Playlists", R.drawable.ic_nav_playlists),
        ;

        companion object {
            /** Bottom-nav tabs, in order. Excludes PLAYLISTS (Settings/landing only). */
            val NAV = listOf(HOME, LIVE, MOVIES, SHOWS, SPORTS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        UiSettings.applyBrightness(this)
        // A fresh launch (brand boot) owns the root /Home landing. Returning to a
        // recreated task — Android Auto auto-resumes us in the background, so the
        // process may be rebuilt while the user isn't looking — must restore the
        // last session instead of dumping the user back on the landing.
        val fresh = consumeFreshMarker()
        if (fresh) clearSession()
        tab = restoredSessionTab(fresh)
        if (intent?.getBooleanExtra(EXTRA_OPEN_SOURCES, false) == true) tab = Tab.PLAYLISTS
        shownAccent = UiSettings.accentIndex(this)
        loadFavorites()
        installBackHandler()
        buildChrome()
        maybeAskForVideoLibrary()
        // Sources are parsed off-thread now (see PlaylistRepository.init), so the
        // first frame can legitimately have no playlist yet. Repaint when they land.
        scope.launch {
            app.repository.loadState.first { it }
            // A build that knows the panel address but carries no login (a customer build) has
            // nothing to show until somebody signs in, so open on the sources screen instead of
            // an empty landing. Builds that seed their own login keep the normal start.
            if (app.repository.playlists.value.isEmpty() &&
                !com.rogueplayer.data.BakedSource.autoSeed
            ) {
                tab = Tab.PLAYLISTS
                selectedGroup = null
            }
            renderContent()
            renderNav()
            reload()
        }
    }

    /**
     * The car's Videos page lists the phone's own video library, which needs
     * READ_MEDIA_VIDEO. A hosted car activity cannot raise that dialog (the projection
     * SDK's CarActivity is a ContextWrapper, not an Activity — javap on aauto-sdk 4.5),
     * so it is asked here, once, on first launch, the way a gallery app would. The
     * "asked" flag means a declined prompt is not a nag; the car page points at
     * Settings instead.
     */
    private fun maybeAskForVideoLibrary() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_VIDEO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ensureRecordingFolder()
            return
        }
        val prefs = getSharedPreferences("mytv_videos", MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        android.util.Log.i("MYTVVideos", "first launch: asking for $perm")
        runCatching { requestPermissions(arrayOf(perm), 4711) }
            .onFailure { android.util.Log.w("MYTVVideos", "request failed", it) }
    }

    /**
     * Once access is allowed, make `Movies/Rogue Player/Recordings` exist on the phone (the
     * gallery album recordings belong in). Runs once per process, off the main thread.
     */
    private var recordingFolderChecked = false

    private fun ensureRecordingFolder() {
        if (recordingFolderChecked) return
        recordingFolderChecked = true
        android.util.Log.i("MYTVVideos", "ensuring the gallery recordings folder")
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            runCatching { com.rogueplayer.data.RecordingStore.ensureFolder(this) }
                .onFailure { android.util.Log.w("MYTVVideos", "folder creation failed", it) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_SOURCES, false) == true) {
            tab = Tab.PLAYLISTS
            selectedGroup = null
            renderContent()
            renderNav()
        }
    }

    /**
     * The EPG guide writes the same favourites prefs file, so pick up anything it
     * changed while we were in the background and repaint the star badges.
     */
    override fun onResume() {
        super.onResume()
        maybeAskForVideoLibrary()
        // Re-apply window brightness + theme accent when returning from Settings
        // (the other activity may have changed them while we were in the background).
        UiSettings.applyBrightness(this)
        if (UiSettings.accentIndex(this) != shownAccent) {
            shownAccent = UiSettings.accentIndex(this)
            // Full rebuild: every screen reads Palette.ACCENTS at build time, so the
            // current tab (and nav) repaint with the new theme, not just the landing.
            renderContent()
            renderNav()
            refreshBrandAccent()   // header wordmark tiles are built once; recolor them
        }
        val before = favorites.toSet()
        loadFavorites()
        if (favorites != before) {
            adapter.favoriteIds = favorites.toSet()
            if (selectedGroup == FAV_FILTER && tab == Tab.LIVE) reload()
            else adapter.notifyDataSetChanged()
        }
    }

    /**
     * Persist where the user is so a recreated task (Android Auto auto-resume,
     * process death) can restore the session instead of re-showing the landing.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) saveSession()
    }

    /**
     * One-shot \"just booted\" marker. Splash sets it before handing off; here we read
     * and clear it so it can only claim \"fresh\" once. A persistent intent extra can't
     * do this job — a killed background task recreates MainActivity with the SAME
     * launch intent, so an extra would report \"fresh\" on every resurrection and the
     * session could never be restored.
     */
    private fun consumeFreshMarker(): Boolean {
        val p = sessionPrefs()
        val fresh = p.getBoolean(FRESH_PENDING, false)
        if (fresh) p.edit().remove(FRESH_PENDING).apply()
        return fresh
    }

    private fun sessionPrefs() = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)

    private fun saveSession() {
        // Skip the transient Sources/Playlists screen, which is a settings surface,
        // not a place the user sits; restoring on it would be surprising.
        if (tab == Tab.PLAYLISTS) return
        sessionPrefs().edit().putInt(SESSION_TAB, tab.ordinal).apply()
    }

    private fun clearSession() = sessionPrefs().edit().clear().apply()

    /** Fresh launch → the configured root (Home landing unless launch-to-Live). */
    private fun restoredSessionTab(fresh: Boolean): Tab {
        if (!fresh) {
            val saved = sessionPrefs().getInt(SESSION_TAB, -1)
            if (saved >= 0) return Tab.entries.getOrNull(saved) ?: Tab.HOME
        }
        return if (UiSettings.launchToLive(this)) Tab.LIVE else Tab.HOME
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
        // The category editor is a sub-screen of the playlists tab, not a tab/filter state.
        if (categoryEditor != null) {
            categoryEditor = null
            renderContent()
            return
        }
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
        h.addView(brandHeader())
        h.addView(TextView(this).apply { // spacer, pushes the actions to the right
            text = ""
            textSize = 10f
            setTextColor(Palette.DIM)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
            setMargins(dp(10), 0, 0, 0)
        })
        searchBtn = TextView(this).apply {
            text = "⌕"
            textSize = 18f
            setTextColor(Palette.MUTED)
            setOnClickListener { showSearch() }
        }
        h.addView(searchBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER_VERTICAL })
        // Visible browser entry (long-press Home also opens it). YouTube has no playable
        // SDK for native apps, so m.youtube.com in a WebView is the only in-app route.
        // Hidden on the /Home landing (the YouTube tile owns that there).
        // Basic edition: a red YouTube play-button; locked keeps the globe icon.
        val ytStyle = resources.getBoolean(R.bool.rp_pill_cards)
        globeBtn = TextView(this).apply {
            text = if (ytStyle) "▶" else "🌐"
            textSize = if (ytStyle) 10.5f else 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            if (ytStyle) {
                // red rounded YouTube badge: red fill + white play triangle
                background = GradientDrawable().apply {
                    cornerRadius = 5 * resources.displayMetrics.density
                    setColor(0xFFE60023.toInt())
                }
                setPadding(dp(5), dp(2), dp(5), dp(2))
            } else {
                setTextColor(Palette.MUTED)
            }
            setOnClickListener { startActivity(BrowserActivity.intent(this@MainActivity)) }
        }
        h.addView(globeBtn, LinearLayout.LayoutParams(dp(32), dp(34)).apply { gravity = Gravity.CENTER_VERTICAL })
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

    /** The tappable Rogue Player wordmark cluster in the header: R tile + ROGUE + player pill. */
    private fun brandHeader(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
            addView(logo("R", 32))
            addView(TextView(this@MainActivity).apply {
                text = "ROGUE"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Palette.TEXT)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                playerPill = TextView(this@MainActivity).apply {
                    text = "player"
                    textSize = 9.5f
                    setTextColor(Color.WHITE)
                    setBackground(rounded(Palette.ACCENTS[2], 6))
                    gravity = Gravity.CENTER
                }
                addView(playerPill!!, LinearLayout.LayoutParams(dp(46), dp(16)))
            })
        }

    /** Single tap on the Rogue Player brand opens the full-screen settings. */
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
            groupsOf(pl) { p -> p.channels.map { it.group } }
                .map { g -> g to (g == selectedGroup) }
        Tab.MOVIES -> listOf("All Movies" to (selectedGroup == null)) +
            groupsOf(pl) { p -> p.movies.map { it.group } }
                .map { g -> g to (g == selectedGroup) }
        Tab.SHOWS -> listOf("All TV Shows" to (selectedGroup == null)) +
            groupsOf(pl) { p -> p.series.map { it.group } }
                .map { g -> g to (g == selectedGroup) }
        Tab.SPORTS -> listOf("All Sports" to (selectedGroup == null)) +
            groupsOf(pl) { p -> p.channels.map { it.group } }
                .filter { isSportGroup(it) }
                .map { g -> g to (g == selectedGroup) }
        else -> emptyList()
    }

    /** Group names that read as sports: keyword match on the category label. */
    private fun isSportGroup(group: String): Boolean {
        val g = group.lowercase()
        return SPORT_KEYWORDS.any { g.contains(it) }
    }

    /** Sports tab: a group-row tap opens that category's channels. */
    private fun openSportGroup(group: String) {
        pushState()
        selectedGroup = group
        reload()
        listView?.scrollToPosition(0)
    }

    /** A playlist's groups minus the ones the user hid, in the saved custom order. */
    private fun groupsOf(pl: Playlist?, pick: (Playlist) -> List<String>): List<String> {
        if (pl == null) return emptyList()
        val hidden = app.repository.hiddenGroups(pl.name)
        val visible = pick(pl).distinct()
            .filter { it.isNotBlank() && it !in hidden }
        return app.repository.orderGroups(pl.name, visible)
    }

    /** (Re)build the chip rows from [defs]; "All" is the first label. */
    private fun renderChips(container: LinearLayout, defs: List<Pair<String, Boolean>>) {
        container.removeAllViews()
        defs.forEach { (label, active) ->
            val isAll = label == "All Channels" || label == "All Movies" ||
                label == "All TV Shows" || label == "All Sections" || label == "All Sports"
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
        Tab.NAV.forEach { t ->
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
            // Long-press Home for the in-app browser (YouTube mobile + anything else).
            if (t == Tab.HOME) {
                item.isLongClickable = true
                item.setOnLongClickListener {
                    startActivity(BrowserActivity.intent(this@MainActivity))
                    true
                }
            }
            // Real vector icons instead of font glyphs: the "▣" used for TV Shows
            // rendered as a square with a solid dot inside, which read as a stray
            // white dot in the bar.
            item.addView(android.widget.ImageView(this).apply {
                setImageResource(t.iconRes)
                setColorFilter(if (active) UiSettings.accent(this@MainActivity, 0) else Palette.MUTED)
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
     * Long-press on the Live TV tab: a one-item menu ("TV guide") that opens the EPG.
     * The old menu also offered "Live channel list", which a normal tap already opens, so it
     * is gone — the user wanted the picker kept, just without the dead option.
     */
    private fun showLiveViewMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Live TV")
            .setItems(arrayOf("TV Guide")) { _, _ ->
                startActivity(Intent(this, GuideActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- body ---

    // ------------------------------------------------------- landing ---

    /** A quick-access landing tile: an accent icon over its label, no card box. */
    private data class LandingTile(
        val label: String,
        val iconRes: Int,
        val action: () -> Unit,
    )

    /** The /Home landing: a 3x2 grid of glowing tile cards over a depth-lit void. */
    private fun renderLanding(col: LinearLayout) {
        col.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL)
        col.setPadding(dp(16), dp(8), dp(16), dp(16))

        // Background is the city image + scrim layered in renderContent; keep the column
        // transparent so the skyline/highway shows through behind the tagline + grid.
        col.background = null

        // Tagline — anchors the grid and carries the brand voice.
        col.addView(TextView(this).apply {
            text = "STREAM EVERYTHING · ONE PLAYER"
            textSize = 11f
            letterSpacing = 0.2f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        col.addView(TextView(this).apply {
            text = "LIVE · MOVIES · TV SHOWS · GUIDE · FOLDERS · YOUTUBE"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Palette.DIM)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val tiles = listOf(
            LandingTile("Live TV", R.drawable.ic_nav_live) { goto(Tab.LIVE) },
            LandingTile("Movies", R.drawable.ic_nav_movies) { goto(Tab.MOVIES) },
            LandingTile("TV Shows", R.drawable.ic_nav_tv) { goto(Tab.SHOWS) },
            LandingTile("TV Guide", R.drawable.ic_nav_guide) {
                startActivity(Intent(this, GuideActivity::class.java))
            },
            LandingTile("Folders", R.drawable.ic_nav_videos) {
                startActivity(Intent(this@MainActivity, FoldersActivity::class.java))
            },
            LandingTile("YouTube", R.drawable.ic_nav_youtube) {
                startActivity(BrowserActivity.intent(this))
            },
        )

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.setPadding(0, dp(14), 0, 0)
        tiles.chunked(2).forEach { row ->
            val rowLay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(0), dp(6), dp(0), dp(6))
            }
            row.forEach { t ->
                rowLay.addView(
                    landingTile(t),
                    LinearLayout.LayoutParams(0, dp(116), 1f).apply {
                        marginStart = dp(6); marginEnd = dp(6)
                    },
                )
            }
            grid.addView(rowLay)
        }
        col.addView(grid)

        // Below the Playlists|YouTube row: a Google-style web-search pill. It opens the
        // in-app browser in web-search mode (Google start page, field focused) so typing
        // searches the whole web — the same route the car uses (the CarStream surface).
        col.addView(webSearchPill(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(74) })  // half a tile-row below the grid
    }

    /** Google-style rounded search pill: accent magnifier + "Search the web" hint. */
    private fun webSearchPill(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = null   // fully transparent per user: no pill container, just text+icon
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { startActivity(BrowserActivity.webIntent(this@MainActivity)) }

            addView(TextView(this@MainActivity).apply {
                text = "\u2315"   // ⌕ magnifier glyph, same as the header search button
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Palette.ACCENTS[0])
            })

            addView(TextView(this@MainActivity).apply {
                text = "Search the web"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Palette.TEXT)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14); marginEnd = dp(6)
            })

            addView(TextView(this@MainActivity).apply {
                text = "\uD83D\uDD0D"   // 🔍 right-side affordance
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Palette.DIM)
            })
        }

    /** One landing tile: clean accent icon over a label — no card box. */
    private fun landingTile(t: LandingTile): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { t.action() }
            addView(
                android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(t.iconRes)
                    // alternate row accent: user's chosen theme accent, violet on the middle row
                    setColorFilter(if (rowOf(t) == 1) UiSettings.accent(this@MainActivity, 2)
                        else UiSettings.accent(this@MainActivity, 0))
                    contentDescription = t.label
                },
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
            addView(TextView(this@MainActivity).apply {
                text = t.label
                textSize = 12.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Palette.TEXT)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
        }

    private fun rowOf(t: LandingTile): Int = when (t.label) {
        "Live TV", "Movies" -> 0
        "TV Shows", "TV Guide" -> 1
        else -> 2
    }

    /** Recolor the header wordmark graphics (R/i tiles + "player" pill) to the current accent. */
    private fun refreshBrandAccent() {
        accentLogos.forEach { it.background = rounded(Palette.ACCENTS[0], 7) }
        playerPill?.background = rounded(Palette.ACCENTS[2], 6)
    }

    /** Switch [target] tab (pushing history so Back returns) and repaint. */
    private fun goto(target: Tab) {
        if (target != tab) pushState()
        selectedGroup = null
        tab = target
        renderContent(); renderNav()
    }

    private fun renderContent() {
        contentPane.removeAllViews()

        // The /Home landing sits over a real city backdrop with a readability scrim.
        if (tab == Tab.HOME) {
            contentPane.addView(
                android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.landing_city)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    contentDescription = null
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT))
            contentPane.addView(View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    // light scrim: keeps the sky dark enough for icons without hiding the city
                    intArrayOf(Color.argb(80, 0, 0, 0), Color.argb(120, 0, 0, 0)))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentPane.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Playlists get their own manager screen (list + update/delete) instead of
        // jumping straight into the add dialog like before.
        if (tab != Tab.PLAYLISTS) categoryEditor = null
        if (tab == Tab.PLAYLISTS) {
            val editing = categoryEditor
            val editingPl = editing?.let { name ->
                app.repository.playlists.value.firstOrNull { it.name == name }
            }
            if (editingPl != null) renderCategories(col, editingPl) else {
                categoryEditor = null
                renderPlaylists(col)
            }
            // This branch returns before the list/nav wiring further down, which is where
            // `attachNavRow()` normally restores the overlay that `removeAllViews()` wiped —
            // so on this screen the bottom nav was detached and the user was stranded.
            renderNav()
            attachNavRow()
            return
        }

        // ---- /Home landing: a grid of big quick-access tiles, no chips or list. ----
        // No floating dock here: a landing shouldn't carry the bottom nav, so we skip
        // attachNavRow() — the dock reappears automatically when a tile navigates to a tab.
        // Keep the MYTV brand (header stays), hide only its action buttons on Home.
        if (tab == Tab.HOME) {
            sortBtn?.visibility = View.GONE
            globeBtn?.visibility = View.GONE
            searchBtn?.visibility = View.GONE
            renderLanding(col)
            return
        }
        sortBtn?.visibility = View.VISIBLE
        globeBtn?.visibility = View.VISIBLE
        searchBtn?.visibility = View.VISIBLE

        col.addView(filterChips())

        // Movies/Shows render as an adaptive Plex-style poster grid; the live
        // list stays single-column.
        val grid = tab == Tab.MOVIES || tab == Tab.SHOWS
        val span = if (grid) gridSpanCount() else 1

        adapter = BrowseAdapter(
            onClickChannel, onClickEntry,
            onEpisode = onClickEpisode,
            onChannelLong = { ch -> toggleFavorite(ch) },
            pillCards = resources.getBoolean(R.bool.rp_pill_cards),
            onGroup = { g -> openSportGroup(g) },
            onScore = { g -> openScore(g) },
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
                return if (item is BrowseItem.SectionTitle || item is BrowseItem.Message ||
                    item is BrowseItem.ScoreCard
                ) span else 1
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

    /**
     * Pull the score slate behind the Sports tab and reload the page when it changes.
     * Throttled so tab-flipping does not stack requests; ScoreSource also caches for a
     * minute and keeps the last good slate when the network fails.
     */
    /**
     * Which leagues a sport category is about, from its group name and the channels in it
     * ("US | NFL Games" → NFL; a hockey group → NHL). Empty when nothing in the group names
     * a league, so the caller can fall back to the broadcast.
     */
    private fun leaguesForCategory(group: String?, channels: List<Channel>): Set<String> {
        val hay = buildString {
            append(group.orEmpty())
            append(' ')
            channels.take(40).forEach { append(it.name).append(' ') }
        }.uppercase()
        val out = LinkedHashSet<String>()
        if (Regex("\\bNFL\\b").containsMatchIn(hay)) out += "NFL"
        if (hay.contains("COLLEGE FOOTBALL") || Regex("\\bNCAAF\\b").containsMatchIn(hay)) out += "NCAAF"
        if (Regex("\\bNBA\\b").containsMatchIn(hay)) out += "NBA"
        if (hay.contains("NCAAB") || hay.contains("COLLEGE BASKETBALL")) out += "NCAAB"
        if (Regex("\\bMLB\\b").containsMatchIn(hay)) out += "MLB"
        if (Regex("\\bNHL\\b").containsMatchIn(hay)) out += "NHL"
        return out
    }

    private fun gamesLabel(n: Int) = if (n == 1) "1 game" else "$n games"

    private fun refreshScores() {
        val now = System.currentTimeMillis()
        if (now - scoreFetchAt < 30_000L) return
        scoreFetchAt = now
        scope.launch {
            val slate = com.rogueplayer.data.ScoreSource.slate()
            if (slate != scoreGames) {
                scoreGames = slate
                reload()
            }
        }
    }

    /**
     * A tap on a game card: find the channels carrying that broadcast and play the single
     * match, offer the list when several match, and say so when none do — never open a
     * stream that has nothing to do with the game.
     */
    private fun openScore(g: com.rogueplayer.data.ScoreGame) {
        val channels = app.repository.activePlaylist?.channels.orEmpty()
        if (channels.isEmpty()) {
            toast("Load a source first")
            return
        }
        val hits = channels.filter { ch ->
            g.broadcasts.any { b -> ch.name.uppercase().contains(b.uppercase()) }
        }
        when {
            hits.size == 1 -> onClickChannel(hits[0])
            hits.size > 1 -> androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("${g.away} at ${g.home}")
                .setItems(hits.take(12).map { it.name }.toTypedArray()) { _, i ->
                    onClickChannel(hits[i])
                }
                .show()
            else -> toast(
                "No channel for " + (g.broadcasts.firstOrNull() ?: "this game") + " in your playlist",
            )
        }
    }

    private fun reload() {
        // The /Home landing has no list/tvPlaceholder, so there's nothing to build.
        if (tab == Tab.HOME) return
        // The Playlists manager draws itself; there is no list page to build. This check has
        // to come BEFORE the "no active source" branch: that branch writes tvPlaceholder,
        // which only exists once a list page has been built — deleting the last source from
        // this tab used to throw UninitializedPropertyAccessException.
        if (tab == Tab.PLAYLISTS) return
        val pl = app.repository.activePlaylist
        if (pl == null) {
            // No active source: the channel adapter may never have been built
            // (e.g. deleted from the Sources screen), so guard the lateinit.
            if (this::adapter.isInitialized) adapter.submitList(emptyList())
            if (this::tvPlaceholder.isInitialized) {
                tvPlaceholder.visibility = View.VISIBLE
                tvPlaceholder.text = "No playlist yet.\nOpen Settings → Playlists to add an M3U link or Xtream login."
            }
            return
        }

        tvPlaceholder.visibility = View.GONE

        // Building a page means sorting/filtering the whole playlist — hundreds of
        // thousands of entries on a big provider. Doing that on the UI thread is
        // what made taps time out (ANR "Input dispatching timed out" in
        // MainActivity). Build it on a single background thread, and drop the
        // result if another reload started meanwhile.
        if (tab == Tab.SPORTS) refreshScores()
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

        // Categories the user hid on this playlist: drop their rows everywhere, and treat a
        // hidden category left over in selectedGroup as "All" so the list can't go blank.
        val hiddenGroups = app.repository.hiddenGroups(pl.name)
        fun <T> visible(rows: List<T>, group: (T) -> String): List<T> =
            if (hiddenGroups.isEmpty()) rows else rows.filterNot { group(it) in hiddenGroups }
        val effectiveFilter = filter?.takeUnless { it in hiddenGroups }

        fun <T> filterBy(rows: List<T>, group: (T) -> String): List<T> =
            effectiveFilter?.let { g -> rows.filter { group(it) == g } } ?: rows

        val chans = if (filter == FAV_FILTER) {
            visible(channels.filter { it.id in favorites }) { it.group }
        } else if (tab == Tab.SPORTS) {
            // Sports tab: only channels in sport-y groups, further narrowed by the
            // selected sport chip (effectiveFilter) when one is chosen.
            val sport = visible(channels.filter { isSportGroup(it.group) }) { it.group }
            filterBy(sport) { it.group }
        } else {
            filterBy(visible(channels) { it.group }) { it.group }
        }
        val movs = filterBy(visible(movies) { it.group }) { it.group }
        val shws = filterBy(visible(shows) { it.group }) { it.group }

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
            Tab.SPORTS -> {
                if (effectiveFilter == null) {
                    // Landing: list the sport GROUPS as tappable rows.
                    val sportGroups = groupsOf(pl) { p -> p.channels.map { it.group } }
                        .filter { isSportGroup(it) }
                    val rows = sportGroups.map { g ->
                        val n = channels.count { it.group == g && it.group !in hiddenGroups }
                        BrowseItem.GroupRow(g, n) as BrowseItem
                    }
                    // Landing shows ONLY games in progress. Upcoming and finished games
                    // live inside the sport categories, so the landing stays a "what's on
                    // right now" screen and is empty of scores when nothing is playing.
                    val scores = scoreGames.filter { it.live }
                    val scoreSection: List<BrowseItem> = if (scores.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(BrowseItem.SectionTitle("Scores", gamesLabel(scores.size)) as BrowseItem) +
                            scores.take(12).map { BrowseItem.ScoreCard(it) as BrowseItem }
                    }
                    scoreSection + listOf(BrowseItem.SectionTitle("Sports Categories",
                        "${Titles.count(sportGroups.size)} groups") as BrowseItem) + rows
                } else {
                    // Inside a sport category: the games this category can actually show
                    // first (its channels carry the broadcast), then its channels.
                    // Inside a category you get what is still to come and what finished —
                    // never a live game (those are the landing's job). A category shows the
                    // slate for the SPORT it covers (an "NFL Games" group shows NFL), and
                    // falls back to broadcast matching when the group names no league.
                    val leagues = leaguesForCategory(filter, chans)
                    val catGames = scoreGames.filter { g ->
                        !g.live && (
                            (leagues.isNotEmpty() && g.league in leagues) ||
                                g.broadcasts.any { b ->
                                    chans.any { ch -> ch.name.uppercase().contains(b.uppercase()) }
                                }
                            )
                    }
                    val scores: List<BrowseItem> = if (catGames.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(BrowseItem.SectionTitle("Scores",
                            gamesLabel(catGames.size)) as BrowseItem) +
                            catGames.take(8).map { BrowseItem.ScoreCard(it) as BrowseItem }
                    }
                    scores + listOf(BrowseItem.SectionTitle(filter,
                        "${Titles.count(chans.size)} channels") as BrowseItem) +
                        chans.map { BrowseItem.ChannelCard(it) as BrowseItem }
                }
            }
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
            pillCards = resources.getBoolean(R.bool.rp_pill_cards),
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
     * Category mask for one playlist: every provider group with a SHOWN/HIDDEN pill.
     * It lives under the playlist card — the place a source is configured/edited — because
     * hiding groups is part of setting a source up, not a separate feature. Hidden groups
     * disappear from Live TV, the guide and the car's category list, but their channels are
     * still stored, so unhiding restores them with no re-fetch.
     */
    private fun renderCategories(col: LinearLayout, pl: Playlist) {
        val repo = app.repository
        val hidden = repo.hiddenGroups(pl.name)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        head.addView(TextView(this).apply {
            text = "\u2190  Playlists"
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
            setBackground(rounded(Palette.CARD_HI, 9))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                categoryEditor = null
                renderContent()
            }
        })
        head.addView(TextView(this).apply {
            text = "Show all"
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
            setBackground(rounded(Palette.ACCENTS[0], 9))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                hidden.toList().forEach { repo.setGroupHidden(pl.name, it, false) }
                toast("All categories shown")
                renderContent()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        col.addView(head)

        col.addView(TextView(this).apply {
            text = "Categories \u00b7 ${pl.name}"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
            setPadding(dp(14), dp(6), dp(14), dp(2))
        })
        col.addView(TextView(this).apply {
            text = if (hidden.isEmpty()) {
                "Tap a category to hide it. Long-press to drag the order. " +
                    "${repo.categories(pl).size} categories, ${pl.channels.size} channels."
            } else {
                "${hidden.size} hidden \u00b7 ${repo.categories(pl).size} categories, " +
                    "${pl.channels.size} channels."
            }
            textSize = 11.5f
            setTextColor(Palette.MUTED)
            setPadding(dp(14), 0, dp(14), dp(8))
        })

        val categories = repo.categories(pl)
        if (categories.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "This playlist has no categories yet \u2014 Update it first."
                setTextColor(Palette.MUTED)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(40), dp(18), dp(40))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }

        // Rows live in a RecyclerView so ItemTouchHelper can lift one and slide it 1:1
        // under the finger: long-press anywhere on a row, or touch the \u2807 grip for an
        // immediate lift. Tap still toggles shown/hidden, and the \u25b2\u25bc arrows stay as a
        // one-handed fallback. The order is written to prefs on drop, not per move.
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            itemAnimator = null              // rows must never animate: drag has to stay 1:1
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(12), 0, dp(12), dp(96))
        }
        val catAdapter = CategoryOrderAdapter(pl, categories.toMutableList(), hidden)
        rv.adapter = catAdapter
        val touch = ItemTouchHelper(catAdapter.dragCallback())
        touch.attachToRecyclerView(rv)
        catAdapter.touchHelper = touch
        col.addView(rv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Drag-reorderable category list for the Categories screen.
     *
     * Order is persisted on DROP (ItemTouchHelper.clearView), not on every move:
     * writing prefs mid-drag bumps groupsVersion and any listener would fight the
     * finger. Tap = hide/show, long-press = lift, grip = lift immediately.
     */
    private inner class CategoryOrderAdapter(
        private val pl: Playlist,
        val items: MutableList<Pair<String, Int>>,
        private val hidden: Set<String>
    ) : RecyclerView.Adapter<CategoryOrderAdapter.Holder>() {

        var touchHelper: ItemTouchHelper? = null
        private var orderDirty = false

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(11), dp(12), dp(11))
                setBackground(rounded(Palette.CARD, 10))
                // RecyclerView defaults to WRAP_CONTENT: without this the cards shrink
                // to their text and stack flush against each other.
                layoutParams = RecyclerView.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = holder.row
            row.removeAllViews()

            val (group, count) = items[position]
            val isHidden = group in hidden

            // Immediate-lift grip. Returning false keeps the touch stream alive so
            // ItemTouchHelper owns the drag from the first pixel of movement.
            row.addView(TextView(this@MainActivity).apply {
                text = "\u2807"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Palette.MUTED)
                setPadding(dp(4), dp(2), dp(10), dp(2))
                isClickable = true
                setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        touchHelper?.startDrag(holder)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }
            })

            val textCol = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(this@MainActivity).apply {
                // Raw provider group, NOT Titles.clean(): that strips the "US | " / "CA | "
                // prefixes, which makes "Entertainment" and "CA | Entertainment" look like
                // the same category in this list.
                text = group.replace("_", " ").trim()
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (isHidden) Palette.MUTED else Palette.TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textCol.addView(TextView(this@MainActivity).apply {
                text = "${Titles.count(count)} channels"
                textSize = 11f
                setTextColor(Palette.MUTED)
            })
            row.addView(textCol, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(reorderArrow("\u25b2", enabled = position > 0) { moveBy(holder, -1) })
            row.addView(reorderArrow("\u25bc", enabled = position < items.size - 1) { moveBy(holder, +1) })
            row.addView(TextView(this@MainActivity).apply {
                text = if (isHidden) "HIDDEN" else "SHOWN"
                textSize = 9.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (isHidden) Palette.MUTED else Color.WHITE)
                setBackground(rounded(if (isHidden) Palette.CARD_HI else Palette.ACCENTS[0], 8))
                setPadding(dp(8), dp(3), dp(8), dp(3))
            })

            row.setOnClickListener {
                app.repository.setGroupHidden(pl.name, group, !isHidden)
                toast((if (isHidden) "Shown: " else "Hidden: ") + group.replace("_", " ").trim())
                renderContent()
            }
        }

        /** Arrow move: shift one row by [delta] without rebuilding the screen. */
        private fun moveBy(holder: Holder, delta: Int) {
            val from = holder.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION) return
            val to = from + delta
            if (to < 0 || to >= items.size) return
            items.add(to, items.removeAt(from))
            notifyItemMoved(from, to)
            persistOrder()
        }

        private fun persistOrder() {
            app.repository.setGroupOrder(pl.name, items.map { it.first })
        }

        fun dragCallback(): ItemTouchHelper.SimpleCallback =
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    rv: RecyclerView,
                    vh: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = vh.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                    items.add(to, items.removeAt(from))
                    notifyItemMoved(from, to)
                    orderDirty = true
                    return true
                }

                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isLongPressDragEnabled(): Boolean = true

                override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    super.clearView(rv, vh)
                    if (orderDirty) {
                        orderDirty = false
                        persistOrder()
                        toast("Category order saved")
                    }
                }
            }
    }

    /** Small tappable up/down arrow for reordering a category row. */
    private fun reorderArrow(glyph: String, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (enabled) Palette.TEXT else Palette.MUTED)
            setBackground(rounded(Palette.CARD_HI, 8))
            setPadding(dp(9), dp(4), dp(9), dp(4))
            alpha = if (enabled) 1f else 0.4f
            isEnabled = enabled
            setOnClickListener { if (enabled) onClick() }
        }

    /**
     * The Playlists screen: one card per saved source with Update / Edit / Categories /
     * Delete, plus an Add button. Tapping a card makes it the active source.
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
                toast("Active: " + (pl.label ?: com.rogueplayer.data.BakedSource.displayName(pl.name)))
                renderContent()
                reload()
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = pl.label ?: com.rogueplayer.data.BakedSource.displayName(pl.name)
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

        // Account expiry straight from the provider's `user_info.exp_date`.
        val (dueText, dueColor) = dueLabel(pl.expiresAt)
        card.addView(TextView(this).apply {
            text = dueText
            textSize = 11.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(dueColor)
            maxLines = 1
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
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
        card.addView(actions)

        // Second row: four buttons on one line overflowed a 904 px screen, which hid
        // Delete completely.
        val actions2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        actions2.addView(cardAction(
            app.repository.hiddenGroups(pl.name).let { hidden ->
                if (hidden.isEmpty()) "👁  Categories" else "👁  Categories (${hidden.size} hidden)"
            }
        ) {
            categoryEditor = pl.name
            renderContent()
        })
        actions2.addView(cardAction("🗑  Delete", Palette.CARD_HI) { confirmDelete(pl) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            })
        card.addView(actions2)

        return card
    }

    /**
     * Expiry line for a playlist card, in plain words: the date the provider reports and
     * how long is left. Blue accents only — this UI has no red — so "expiring" and
     * "expired" both use the accent, with the wording carrying the warning.
     */
    private fun dueLabel(expiresAt: Long?): Pair<String, Int> {
        if (expiresAt == null) return "No expiry reported by provider" to Palette.DIM
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val days = ((expiresAt - now) / dayMs).toInt()
        val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(expiresAt))
        return when {
            days < 0 -> "Expired $date" to Palette.ACCENTS[0]
            days == 0 -> "Due today ($date)" to Palette.ACCENTS[0]
            days <= 7 -> "Due $date · $days days left" to Palette.ACCENTS[0]
            else -> "Due $date · $days days left" to Palette.MUTED
        }
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
        // Same three modes as the add dialog; a source that came from the baked
        // panel opens on Rogue TV, where the address is not shown at all.
        val tabRogue = fields.findViewById<TextView>(R.id.tab_roguetv)
        val baked = com.rogueplayer.data.BakedSource.enabled &&
            pl.kind == PlaylistKind.XTREAM &&
            pl.url.startsWith(com.rogueplayer.data.BakedSource.base(), ignoreCase = true)
        tabRogue.visibility = if (com.rogueplayer.data.BakedSource.enabled) View.VISIBLE else View.GONE
        var mode = when {
            baked -> 2
            pl.kind == PlaylistKind.XTREAM -> 1
            else -> 0
        }
        fun paint(t: TextView, on: Boolean) {
            t.setBackgroundColor(if (on) Palette.CARD_HI else Palette.CARD)
            t.setTextColor(if (on) Palette.TEXT else Palette.MUTED)
        }
        fun syncTabs() {
            urlEdit.visibility = if (mode == 0) View.VISIBLE else View.GONE
            hostEdit.visibility = if (mode == 1) View.VISIBLE else View.GONE
            val creds = if (mode == 0) View.GONE else View.VISIBLE
            userEdit.visibility = creds; passEdit.visibility = creds
            paint(tabM3u, mode == 0); paint(tabXtream, mode == 1); paint(tabRogue, mode == 2)
        }
        tabM3u.setOnClickListener { mode = 0; syncTabs() }
        tabXtream.setOnClickListener { mode = 1; syncTabs() }
        tabRogue.setOnClickListener { mode = 2; syncTabs() }

        nameEdit.setText(pl.label ?: com.rogueplayer.data.BakedSource.displayName(pl.name))
        // Stored Xtream sources keep the full player_api URL; the field wants the host.
        hostEdit.setText(
            when {
                baked -> com.rogueplayer.data.BakedSource.base()
                pl.kind == PlaylistKind.XTREAM -> pl.url.substringBefore("/player_api.php")
                else -> ""
            }
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
                    val target = when (mode) {
                        2 -> com.rogueplayer.data.BakedSource.base()
                        1 -> hostEdit.text.toString().trim()
                        else -> urlEdit.text.toString().trim()
                    }
                    if (target.isBlank()) {
                        toast("Enter the host or URL")
                        return@setOnClickListener
                    }
                    scope.launch {
                        val result = runCatching {
                            PlaylistParser.load(
                                if (mode != 0) PlaylistKind.XTREAM else PlaylistKind.M3U,
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
                            app.repository.setLabel(pl.name, nameEdit.text.toString())
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
            .setMessage("Remove “${pl.label ?: com.rogueplayer.data.BakedSource.displayName(pl.name)}” from this device? The provider account itself is untouched.")
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
        val tabRogue = fields.findViewById<TextView>(R.id.tab_roguetv)

        // Three ways in: M3U link, your own Xtream panel, or Rogue TV — the baked
        // panel, where only credentials are typed and the address is never shown.
        // The tab only exists in a build that carries a baked portal.
        val baked = com.rogueplayer.data.BakedSource.enabled
        tabRogue.visibility = if (baked) View.VISIBLE else View.GONE
        if (baked) nameEdit.setText(com.rogueplayer.data.BakedSource.LABEL)

        var mode = if (baked) 2 else 1
        fun paint(t: TextView, on: Boolean) {
            t.setBackgroundColor(if (on) Palette.CARD_HI else Palette.CARD)
            t.setTextColor(if (on) Palette.TEXT else Palette.MUTED)
        }
        fun refresh() {
            val m3u = mode == 0
            urlEdit.visibility = if (m3u) View.VISIBLE else View.GONE
            // Server field only for a panel the user supplies; Rogue TV hides it.
            hostEdit.visibility = if (mode == 1) View.VISIBLE else View.GONE
            val creds = if (mode == 0) View.GONE else View.VISIBLE
            userEdit.visibility = creds; passEdit.visibility = creds
            paint(tabM3u, mode == 0); paint(tabXtream, mode == 1); paint(tabRogue, mode == 2)
        }
        tabM3u.setOnClickListener { mode = 0; refresh() }
        tabXtream.setOnClickListener { mode = 1; refresh() }
        tabRogue.setOnClickListener { mode = 2; refresh() }
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
                            val host = if (mode == 2) com.rogueplayer.data.BakedSource.base()
                                       else hostEdit.text.toString().trim()
                            // Rogue TV supplies the address, never the account: the fields are
                            // the customer's own login and both are required.
                            if (mode != 0 && (userEdit.text.isBlank() || passEdit.text.isBlank())) {
                                toast("Enter your username and password")
                                return@launch
                            }
                            val src = if (mode != 0) {
                                PlaylistParser.load(PlaylistKind.XTREAM,
                                    host,
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
        accentLogos.add(tile)
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
        /** Intent extra: open straight into the Sources/Playlists tab (from Settings). */
        const val EXTRA_OPEN_SOURCES = "open_sources"
        /** Intent extra: set by the brand boot — the landing is the root only on a fresh launch. */
        const val EXTRA_FRESH_LAUNCH = "fresh_launch"
        /** One-shot marker the splash sets and MainActivity consumes (see consumeFreshMarker). */
        const val FRESH_PENDING = "fresh_pending"
        const val SESSION_PREFS = "mytv_session"
        const val SESSION_TAB = "tab"
        private const val MENU_LIVE_LIST = 1
        private const val MENU_GUIDE = 2

        /** Group-name keywords that make a category show under the Sports tab. */
        val SPORT_KEYWORDS = listOf(
            "sport", "nba", "nfl", "mlb", "nhl", "mls", "wnba", "ncaa", "espn",
            "ufc", "boxing", "wwe", "motogp", "f1", "formula", "nascar", "indycar",
            "soccer", "football", "basketball", "baseball", "hockey", "tennis",
            "golf", "cricket", "rugby", "league", "cup", "fight", "ppv", "racing",
            "olympic", "epl", "champions", "euros", "premier", "laliga", "serie a",
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
