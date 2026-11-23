package com.rogueplayer.legacycar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.text.TextUtils
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.support.car.input.CarRestrictedEditText
import com.google.android.apps.auto.sdk.CarImeBridge
import com.google.android.apps.auto.sdk.CarActivity
import com.rogueplayer.MyApplication
import com.rogueplayer.R
import com.rogueplayer.data.Channel
import com.rogueplayer.data.Entry
import com.rogueplayer.data.EpgProgram
import com.rogueplayer.data.Episode
import com.rogueplayer.data.PlaylistKind
import com.rogueplayer.player.PlaybackService
import com.rogueplayer.ui.EpgGridView
import com.rogueplayer.ui.Palette
import com.rogueplayer.ui.PosterCell
import com.rogueplayer.ui.SplashView
import com.rogueplayer.ui.UiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The LEGACY edition's car screen — a real hosted activity (CarStream/Fermata
 * route), not a template session. Everything is plain Views in a real window:
 * taps are single-tap, back is real, and finishing returns to the AA launcher.
 *
 * Navigation is a tiny page stack: each page is a title + a view factory. The
 * root is the landing (splash first). Back at the root exits the car app via
 * the SDK's host proxy ([exitCarApp]) — the modern library has no such lever.
 *
 * Playback reuses the shared [PlaybackService] (bound, never started — same
 * rule as the modern car session). Leaving the video page keeps the audio
 * running; lists show a "Now playing" row to jump back in.
 */
class LegacyCarActivity : CarActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: FrameLayout
    private val repo get() = (applicationContext as MyApplication).repository

    // ---------------------------------------------------------- playback ---
    private var svc: PlaybackService? = null
    private var binding = false
    private var pendingPlay: Pair<String, String>? = null
    private var pendingSurface: Surface? = null

    // --------------------------------------------------------------- guide ---
    /** Guide data by channel id — activity-level so leaving and re-entering the
     *  guide never re-fetches what is already loaded. */
    private val guideEpg = mutableMapOf<String, List<EpgProgram>>()
    private val guideRequested = mutableSetOf<String>()
    private val guideRetryAt = mutableMapOf<String, Long>()
    private var guideLoadJob: Job? = null
    private var guideInFlight = 0
    private var guideHits = 0
    private var guideFailures = 0
    private var guideDisabled = false
    private var guideGrid: EpgGridView? = null
    private var guideStatus: TextView? = null
    private var guideQueued: IntRange = 0..0
    /** Scroll fires a range change per frame; only start a fetch once it settles. */
    private val guideDebounce = Runnable { guideGrid?.let { loadGuide(it) } }

    /** Gutter preview pane (TiViMate-style): a live picture of the highlighted
     *  channel in the left rail. Toggleable — off, the grid takes the rail back. */
    private val guidePrefs by lazy { getSharedPreferences("rogue_car_guide", Context.MODE_PRIVATE) }
    private var guideChannels: List<Channel> = emptyList()
    private var guideOpen = false
    private var previewOn = true
    private var previewChannel: Channel? = null
    private var previewSurface: Surface? = null
    private var previewName: TextView? = null
    private var previewNow: TextView? = null
    private var previewNext: TextView? = null
    private var previewTrack: View? = null
    private var previewFill: View? = null
    private var previewHint: TextView? = null
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

    /** Keeps the pane's now/next line and progress bar in step without re-fetching. */
    private val previewTicker = object : Runnable {
        override fun run() {
            updatePreviewInfo()
            if (guideOpen && previewOn) handler.postDelayed(this, 5_000L)
        }
    }

    /**
     * The host's own input manager — the thing that raises the head-unit
     * keyboard. `Car.createCar` does NOT work here (it dials the embedded car
     * service, which a phone does not have), but a hosted CarActivity IS a
     * [com.google.android.gms.car.CarActivityHost], so the manager is local.
     */
    /** True between our startInput and stopInput, so we never stack requests. */
    private var keyboardRequested = false

    private val hostInput: com.google.android.gms.car.input.InputManager?
        by lazy {
            runCatching {
                // The host lives in the private `a` field of gms `c` (the same
                // object exitCarApp() uses to finish the hosted activity); the
                // activity itself is NOT a CarActivityHost.
                val f = com.google.android.gms.car.c::class.java.getDeclaredField("a")
                f.isAccessible = true
                val host = f.get(this) as com.google.android.gms.car.CarActivityHost
                host.inputManager
            }.onFailure { Log.w(TAG, "host InputManager unavailable", it) }.getOrNull()
        }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = (binder as? PlaybackService.LocalBinder)?.getService()
            binding = false
            pendingPlay?.let { (url, title) ->
                pendingPlay = null
                svc?.play(url, title, 0L)
            }
            pendingSurface?.let { s ->
                pendingSurface = null
                svc?.setVideoSurface(s)
            }
            // The guide's rail may have been waiting for this binding.
            if (previewChannel != null && previewOn) attachPreviewSurface()
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    private fun bindPlayback() {
        if (svc != null || binding) return
        binding = runCatching {
            bindService(Intent(this, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "bind to playback failed", it)
            false
        }
    }

    private fun play(url: String, title: String) {
        if (url.isBlank()) return
        val s = svc
        if (s != null) s.play(url, title, 0L)
        else { pendingPlay = url to title; bindPlayback() }
        show(videoPage(title))
    }

    // -------------------------------------------------------- navigation ---
    private class Page(val title: String, val build: () -> View)
    private val backStack = ArrayDeque<Page>()
    private var current: Page? = null

    private fun show(page: Page, pushCurrent: Boolean = true) {
        dropKeyboard()
        Log.i(TAG, "show page: ${page.title} (stack=${backStack.size})")
        // The guide's rail surface dies with its page; stop the pane's clock too.
        guideOpen = false
        handler.removeCallbacks(previewTicker)
        if (pushCurrent) current?.let { backStack.addLast(it) }
        current = page
        root.removeAllViews()
        root.addView(page.build(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun back() {
        val prev = backStack.removeLastOrNull()
        if (prev == null) exitCarApp() else show(prev, pushCurrent = false)
    }

    override fun onBackPressed() { back() }

    /**
     * Real exit: the SDK keeps a private [com.google.android.gms.car.CarActivityHost]
     * proxy whose finish() tells gearhead to close the hosted activity — the host
     * then falls back to the AA launcher. Field name is pinned to aauto-sdk v4.5.
     * Fallback: the SDK's own back handling (unhandled back closes the screen).
     */
    private fun exitCarApp() {
        runCatching { svc?.pause() }
        val ok = runCatching {
            val f = com.google.android.gms.car.c::class.java.getDeclaredField("a")
            f.isAccessible = true
            (f.get(this) as com.google.android.gms.car.CarActivityHost).finish()
        }.isSuccess
        Log.i(TAG, "exit: host.finish() ok=$ok")
        if (!ok) super.onBackPressed()
    }

    // ---------------------------------------------------------- lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        setContentView(root)
        runCatching { carUiController.statusBarController.hideAppHeader() }
        bindPlayback()
        // The host creates this activity at BIND time, seconds before the window
        // is shown, so the boot starts on the first real window focus
        // (onWindowFocusChanged); this delayed fallback only covers hosts that
        // never report focus.
        handler.postDelayed({
            if (!bootStarted) {
                Log.i(TAG, "boot: no focus after 3s, starting anyway")
                startBoot()
            }
        }, 3000L)
    }

    private var bootStarted = false
    private var bootFinished = false

    private fun startBoot() {
        if (bootStarted) return
        bootStarted = true
        Log.i(TAG, "boot: playing splash")
        root.removeAllViews()
        val sv = SplashView(this)
        root.addView(sv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The legacy host re-renders the hierarchy on LAYOUT changes, not on
        // invalidate() alone — the Choreographer animation ran its full 4.2s
        // invisibly (logs proved start/done; owner saw nothing). Pump a layout
        // request every frame so each animation frame reaches the host renderer.
        // Owner report (real car): "it tried but didn't render". So log what the
        // host actually asks for — one size line plus a heartbeat every 20 frames
        // tells us whether frames reach the host at all, or whether the splash is
        // being torn down before it paints.
        val pump = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (bootFinished) return
                sv.requestLayout()
                sv.invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(pump)
        sv.start {
            bootFinished = true
            Log.i(TAG, "boot: done -> landing")
            handler.post { show(landingPage(), pushCurrent = false) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean, inTouchMode: Boolean) {
        super.onWindowFocusChanged(hasFocus, inTouchMode)
        Log.i(TAG, "window focus=$hasFocus")
        if (hasFocus && !bootStarted) startBoot()
    }

    override fun onStart() {
        super.onStart()
        bindPlayback()
    }

    override fun onStop() {
        // Leaving the car screen (app switch or exit): pause and let the service
        // binding go so the process is not held alive after a real exit.
        dropKeyboard()
        runCatching { svc?.pause() }
        runCatching { unbindService(conn) }
        svc = null
        binding = false
        super.onStop()
    }

    override fun onDestroy() {
        dropKeyboard()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ landing ---
    private fun landingPage(): Page = Page("Rogue") { buildLanding() }

    private fun buildLanding(): View {
        val host = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        // Scrollable so nothing is ever unreachable on a short/landscape head
        // unit: the DHU's near-square window fits the whole column, a real car
        // display (wide, low) used to clip the search + exit pills off the
        // bottom. fillViewport keeps the column vertically centred when it does
        // fit, so the DHU look is unchanged.
        val centre = FrameLayout(this)
        centre.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(centre, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        host.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        col.addView(text("STREAM EVERYTHING · ONE PLAYER", 12f, Palette.MUTED, true))
        col.addView(text("LEGACY EDITION · real exit, no boot hijack", 10f, Palette.DIM, false).apply {
            gravity = Gravity.CENTER
        })

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        val tiles = listOf(
            Tile("Live TV", R.drawable.ic_nav_live, 0) { show(liveGroupsPage()) },
            Tile("Movies", R.drawable.ic_nav_movies, 0) { show(movieGroupsPage()) },
            Tile("TV Shows", R.drawable.ic_nav_tv, 1) { show(showGroupsPage()) },
            Tile("YouTube", R.drawable.ic_nav_youtube, 1) {
                show(webPage("YouTube", "https://m.youtube.com", YOUTUBE_SEARCH))
            },
        )
        // Search sits in the MIDDLE of the tile grid, not under it. The bottom of
        // a vertically-centred column is the first thing a short landscape head
        // unit clips, which is how the old bottom pill went missing in the car
        // while it still showed on the DHU.
        val search = Tile("Search", R.drawable.ic_nav_search, 0) {
            show(webPage("Google", "https://www.google.com", GOOGLE_SEARCH))
        }
        val base = tiles.chunked(2)
        val rows = buildList {
            add(base[0])
            add(listOf(search))
            addAll(base.drop(1))
        }
        rows.forEach { row ->
            val rowLay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            // A one-tile row stays a third of the width and centred, so the
            // magnifier reads as its own thing between the two tile rows.
            if (row.size == 1) {
                rowLay.addView(View(this), LinearLayout.LayoutParams(0, 1, 0.5f))
                rowLay.addView(tileView(row[0]), LinearLayout.LayoutParams(0, dp(110), 1f))
                rowLay.addView(View(this), LinearLayout.LayoutParams(0, 1, 0.5f))
            } else {
                row.forEach { t -> rowLay.addView(tileView(t), LinearLayout.LayoutParams(0, dp(110), 1f).apply {
                    marginStart = dp(6); marginEnd = dp(6)
                }) }
            }
            grid.addView(rowLay)
        }
        col.addView(grid)

        col.addView(pill("✕  Exit to Android Auto") { exitCarApp() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) })
        return host
    }

    private class Tile(val label: String, val icon: Int, val accentRow: Int, val open: () -> Unit)

    private fun tileView(t: Tile): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { t.open() }
        addView(ImageView(this@LegacyCarActivity).apply {
            setImageResource(t.icon)
            setColorFilter(UiSettings.accent(this@LegacyCarActivity, t.accentRow))
            contentDescription = t.label
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(text(t.label, 13f, Palette.TEXT, true).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun pill(label: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { onClick() }
            addView(text(label, 14f, Palette.TEXT, true))
        }

    // -------------------------------------------------------------- lists ---
    private class Row(val title: String, val subtitle: String?, val onClick: () -> Unit)

    /** A single trailing control in a page header (e.g. the Guide button). */
    private class HeaderAction(val label: String, val onClick: () -> Unit)

    private fun listPage(
        title: String,
        action: HeaderAction? = null,
        rows: () -> List<Row>,
    ): Page {
        lateinit var page: Page
        page = Page(title) {
            if (!repo.loadState.value) {
                scope.launch {
                    repo.loadState.first { it }
                    if (current === page) show(page, pushCurrent = false)
                }
                column(title, action) { addView(text("Loading your sources…", 14f, Palette.MUTED, false)) }
            } else buildList(title, rows(), action)
        }
        return page
    }

    private fun buildList(title: String, rows: List<Row>, action: HeaderAction? = null): View {
        val all = ArrayList<Row>()
        svc?.currentTitle?.takeIf { it.isNotBlank() }?.let { t ->
            all.add(Row("▶ Now playing", t) { show(videoPage(t)) })
        }
        all.addAll(rows)
        val list = ListView(this).apply {
            adapter = RowAdapter(all)
            divider = android.graphics.drawable.ColorDrawable(Color.argb(40, 255, 255, 255))
            dividerHeight = 1
            cacheColorHint = Color.TRANSPARENT
        }
        return column(title, action) {
            addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private inner class RowAdapter(val rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val v = (convertView as? LinearLayout) ?: LinearLayout(this@LegacyCarActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
                minimumHeight = dp(56)
            }
            v.removeAllViews()
            v.addView(text(row.title, 15f, Palette.TEXT, true).apply { gravity = Gravity.START })
            row.subtitle?.let {
                v.addView(text(it, 11.5f, Palette.MUTED, false).apply { gravity = Gravity.START })
            }
            v.setOnClickListener { row.onClick() }
            return v
        }
    }

    /** Header (back + title + optional trailing action) over a content column. */
    private fun column(
        title: String,
        action: HeaderAction? = null,
        fill: LinearLayout.() -> Unit,
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        header.addView(text("‹  Back", 14f, Palette.ACCENTS[0], true).apply {
            isClickable = true
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setOnClickListener { back() }
        })
        header.addView(text(title, 16f, Palette.TEXT, true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Trailing control. One tap: this is a real window, not a template strip.
        action?.let { a ->
            header.addView(text(a.label, 14f, Palette.ACCENTS[0], true).apply {
                isClickable = true
                isFocusable = true
                setPadding(dp(14), dp(8), dp(6), dp(8))
                setOnClickListener { a.onClick() }
            })
        }
        col.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fill()
        }
        col.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    // ---------------------------------------------------------- live tv ---
    private fun liveGroupsPage(): Page = listPage(
        "Live TV",
        HeaderAction("Guide ▸") { show(guidePage(null)) },
    ) {
        val pl = repo.activePlaylist
        if (pl == null || pl.channels.isEmpty()) {
            listOf(Row("No playlist loaded", "Add an M3U or Xtream source from the phone app") {})
        } else {
            val hidden = repo.hiddenGroups(pl.name)
            val shown = pl.channels.filterNot { it.group in hidden }
            val favIds = favourites()
            val out = ArrayList<Row>()
            val favs = shown.filter { it.id in favIds }
            if (favs.isNotEmpty()) {
                out.add(Row("★ Favourites", "${favs.size} channels") {
                    show(channelsPage("Favourites", favs))
                })
            }
            shown.groupBy { it.group.ifBlank { "Ungrouped" } }.toSortedMap().forEach { (g, chs) ->
                out.add(Row(g, "${chs.size} channels") { show(channelsPage(g, chs)) })
            }
            out
        }
    }

    private fun channelsPage(title: String, channels: List<Channel>): Page =
        listPage(
            title,
            HeaderAction("Guide ▸") { show(guidePage(channels)) },
        ) {
            channels.map { ch -> Row(ch.name, ch.group) { play(ch.url, ch.name) } }
        }

    // ------------------------------------------------------------- guide ---
    /**
     * TV guide on the car screen: the same [EpgGridView] the phone uses, with no
     * preview pane — tap a programme and that channel plays full screen.
     *
     * [scoped] is the channel list to show (a category you opened); null means
     * every channel of the active playlist that is not in a hidden category.
     *
     * Guide data is fetched per channel as rows become visible, exactly like the
     * phone: an Xtream panel only exposes a per-channel endpoint, so asking for a
     * whole day at once is what gets the socket reset. Loaded data is kept in
     * [guideEpg] at activity level, so backing out and returning to the guide does
     * not re-fetch anything.
     */
    private fun guidePage(scoped: List<Channel>?): Page = Page("TV Guide") guide@{
        val pl = repo.activePlaylist
        if (pl == null || pl.channels.isEmpty()) {
            return@guide column("TV Guide") {
                addView(text("No channels. Add a playlist from the phone app.", 14f, Palette.MUTED, false))
            }
        }
        if (pl.kind != PlaylistKind.XTREAM) {
            // Same rule as the phone guide: M3U playlists carry no XMLTV here.
            return@guide column("TV Guide") {
                addView(text("Guide data needs an Xtream source (this playlist is M3U).", 14f, Palette.MUTED, false))
            }
        }
        val hidden = repo.hiddenGroups(pl.name)
        val list = (scoped ?: pl.channels.filterNot { it.group in hidden })
            .filterNot { it.group in hidden }
        if (list.isEmpty()) {
            return@guide column("TV Guide") {
                addView(text("No channels in this category.", 14f, Palette.MUTED, false))
            }
        }
        buildGuide(list)
    }

    private fun buildGuide(channels: List<Channel>): View {
        guideChannels = channels
        guideOpen = true
        previewOn = guidePrefs.getBoolean(KEY_PREVIEW, true)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }
        header.addView(text("‹  Back", 14f, Palette.ACCENTS[0], true).apply {
            isClickable = true
            setPadding(dp(12), dp(8), dp(14), dp(8))
            setOnClickListener { back() }
        })
        header.addView(text("TV GUIDE", 15f, Palette.TEXT, true).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = text("", 11.5f, Palette.MUTED, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
        }
        header.addView(status)
        header.addView(text("Now", 14f, Palette.ACCENTS[0], true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { guideGrid?.scrollToNow() }
        })
        // Preview toggle: grid-only or grid + live pane. Remembered per unit.
        header.addView(text(
            if (previewOn) "◉ Preview" else "○ Preview",
            13.5f, if (previewOn) Palette.ACCENTS[0] else Palette.MUTED, true,
        ).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { togglePreview() }
        })
        col.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Size the pane like a poster, not a thumbnail: a 16:9 picture has to be
        // WIDER than a 2:3 poster cell (~140x210dp here) to carry the same weight,
        // so the rail takes a third of the window with a 240dp floor.
        val winDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val railDp = maxOf(240f, winDp * 0.33f)
        val rail = buildPreviewRail(railDp)
        body.addView(rail, LinearLayout.LayoutParams(dp(railDp.toInt()), ViewGroup.LayoutParams.MATCH_PARENT))
        rail.visibility = if (previewOn) View.VISIBLE else View.GONE

        val grid = EpgGridView(this).apply {
            rows = channels.map { EpgGridView.Row(it, guideEpg[it.id].orEmpty()) }
            onChannelClick = { ch ->
                selectedChannelId = ch.id
                invalidate()
                if (previewOn) startPreview(ch)
            }
            // One tap on a block plays it full screen; the gutter only selects.
            onProgramClick = { ch, _ -> play(ch.url, ch.name) }
            onVisibleRangeChanged = { range ->
                guideQueued = range
                handler.removeCallbacks(guideDebounce)
                handler.postDelayed(guideDebounce, 400L)
            }
            begin()
        }
        guideGrid = grid
        guideStatus = status
        body.addView(grid, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        col.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Prime what is on screen once the view has been laid out (visibleRows()
        // needs real dimensions). begin() already ran, but it centred the timeline
        // on a width of 0, so centring again here lands on the actual "now".
        grid.post {
            grid.scrollToNow()
            loadGuide(grid)
        }
        grid.postDelayed({ if (guideGrid === grid) grid.invalidate() }, 250L)

        // Resuming a guide that already had a picture: put the stream back on this
        // rail's TextureView (the old one was destroyed with the previous page).
        previewChannel?.let { ch ->
            if (previewOn) {
                previewName?.text = ch.name
                previewHint?.visibility = View.GONE
                attachPreviewSurface()
            }
        }
        updatePreviewInfo()
        handler.removeCallbacks(previewTicker)
        if (previewOn && previewChannel != null) handler.postDelayed(previewTicker, 5_000L)
        return col
    }

    /** Left rail: live picture of the highlighted channel + what is on. */
    private fun buildPreviewRail(railDp: Float): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.CARD)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        rail.addView(text("PREVIEW · LIVE", 9.5f, Palette.MUTED, true).apply {
            letterSpacing = 0.14f
            setPadding(0, 0, 0, dp(7))
        })

        // the picture keeps 16:9 at whatever width the rail ended up
        val cardW = railDp - 20f
        val cardH = cardW * 9f / 16f
        val card = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#111A2B")) }
        val tv = TextureView(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                previewSurface = Surface(st)
                attachPreviewSurface()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                previewSurface = null
                runCatching { svc?.setVideoSurface(null) }
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        card.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewHint = text("Tap a channel", 11f, Palette.MUTED, false).apply {
            gravity = Gravity.CENTER
        }
        card.addView(previewHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rail.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(cardH.toInt())))
        previewFill = View(this).apply { setBackgroundColor(Palette.ACCENTS[0]) }

        previewName = text("—", 16f, Palette.TEXT, true).apply {
            setPadding(0, dp(10), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        rail.addView(previewName)
        previewNow = text("", 11.5f, Palette.TEXT, false).apply {
            setPadding(0, dp(5), 0, 0)
            maxLines = 2
        }
        rail.addView(previewNow)
        previewNext = text("", 10.5f, Palette.MUTED, false).apply {
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        rail.addView(previewNext)

        // progress: a 3dp track with a fill whose width is set per tick
        val track = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1B1B26"))
        }
        track.addView(previewFill, FrameLayout.LayoutParams(0, dp(3)))
        previewTrack = track
        rail.addView(track, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(10) })
        return rail
    }

    /** Point the shared player at the rail's surface (no-op until both exist). */
    private fun attachPreviewSurface() {
        val s = previewSurface ?: return
        (svc ?: run { bindPlayback(); return }).setVideoSurface(s)
    }

    /** Play [ch] into the rail instead of full screen. */
    private fun startPreview(ch: Channel) {
        previewChannel = ch
        previewName?.text = ch.name
        previewHint?.visibility = View.GONE
        previewNow?.text = "tuning…"
        previewNext?.text = ""
        val s = svc
        if (s == null) {
            pendingPlay = ch.url to ch.name
            bindPlayback()
        } else {
            attachPreviewSurface()
            s.play(ch.url, ch.name)
        }
        handler.removeCallbacks(previewTicker)
        handler.postDelayed(previewTicker, 1_000L)
        updatePreviewInfo()
    }

    /** Now/next + progress for the pane, from the guide data already loaded. */
    private fun updatePreviewInfo() {
        val ch = previewChannel ?: return
        val progs = guideEpg[ch.id].orEmpty()
        val now = System.currentTimeMillis()
        val cur = progs.firstOrNull { it.contains(now) }
        if (cur != null) {
            previewNow?.text = "Now · " + cur.title + "\n" +
                timeFmt.format(java.util.Date(cur.start)) + "–" + timeFmt.format(java.util.Date(cur.end))
        } else if (progs.isEmpty()) {
            previewNow?.text = "No guide data"
        } else {
            previewNow?.text = "—"
        }
        val next = progs.firstOrNull { it.start >= (cur?.end ?: now) }
        previewNext?.text = next?.let { "Next · " + it.title + " · " + timeFmt.format(java.util.Date(it.start)) }
            .orEmpty()

        val frac = if (cur != null) {
            ((now - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1L)).coerceIn(0f, 1f)
        } else 0f
        val track = previewTrack ?: return
        val fill = previewFill ?: return
        val lp = fill.layoutParams ?: return
        lp.width = (track.width * frac).toInt()
        fill.layoutParams = lp
    }

    private fun togglePreview() {
        previewOn = !previewOn
        guidePrefs.edit().putBoolean(KEY_PREVIEW, previewOn).apply()
        if (!previewOn) {
            // Rail gone: stop the picture that was only there to be seen.
            runCatching { svc?.pause() }
            previewChannel = null
            handler.removeCallbacks(previewTicker)
        }
        show(guidePage(guideChannels), pushCurrent = false)
    }


    /**
     * Fetch guide data for the rows on screen (plus a small margin). Requests are
     * spaced out and failures back off per channel, because an Xtream panel
     * answers a burst with a socket reset — the phone guide learned this the hard
     * way and the car must not repeat it.
     */
    private fun loadGuide(grid: EpgGridView) {
        if (guideDisabled) return
        val rows = grid.rows
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        val range = grid.visibleRows()
        val from = (range.first - 1).coerceAtLeast(0)
        val to = (range.last + 2).coerceAtMost(rows.size - 1)
        val todo = (from..to).mapNotNull { i ->
            rows.getOrNull(i)?.channel?.takeIf { ch ->
                ch.id !in guideRequested && (guideRetryAt[ch.id] ?: 0L) <= now
            }
        }
        if (todo.isEmpty()) {
            updateGuideStatus()
            return
        }
        todo.forEach { guideRequested.add(it.id) }
        guideInFlight += todo.size
        updateGuideStatus()

        guideLoadJob?.cancel()
        guideLoadJob = scope.launch {
            val pending = ArrayDeque(todo)
            try {
                while (pending.isNotEmpty()) {
                    if (guideDisabled) return@launch
                    val ch = pending.removeFirst()
                    val started = System.currentTimeMillis()
                    val result = withContext(Dispatchers.IO) {
                        runCatching { repo.epgFor(ch) }
                    }
                    // A reply this fast came off the disk cache — no need to space it.
                    val fromCache = System.currentTimeMillis() - started < 60
                    val epg = result.getOrNull().orEmpty()
                    if (epg.isNotEmpty()) {
                        guideHits += 1
                        guideFailures = 0
                        guideRetryAt.remove(ch.id)
                    } else if (result.isFailure) {
                        // Empty listings are legitimate (PPV channels have none);
                        // only real errors count toward giving up.
                        guideFailures += 1
                        guideRequested.remove(ch.id)
                        guideRetryAt[ch.id] = System.currentTimeMillis() + 45_000L
                        if (guideHits == 0 && guideFailures >= 20) {
                            guideDisabled = true
                            guideStatus?.text = "no guide data from this source"
                        }
                    } else {
                        guideFailures = 0
                    }
                    applyGuide(ch, epg)
                    guideInFlight -= 1
                    updateGuideStatus()
                    if (!fromCache) delay(350L)
                }
            } finally {
                pending.forEach { guideRequested.remove(it.id) }
                guideInFlight -= pending.size
                updateGuideStatus()
            }
        }
    }

    private fun applyGuide(channel: Channel, epg: List<EpgProgram>) {
        guideEpg[channel.id] = epg
        val grid = guideGrid ?: return
        val index = grid.rows.indexOfFirst { it.channel.id == channel.id }
        if (index < 0) return
        grid.rows[index].programmes = epg
        grid.loadedChannels.add(channel.id)
        grid.invalidate()
    }

    private fun updateGuideStatus() {
        val s = guideStatus ?: return
        if (guideGrid == null) return
        s.text = when {
            guideInFlight > 0 -> "loading $guideInFlight…"
            else -> ""
        }
    }

    // ------------------------------------------------------------- movies ---
    private fun movieGroupsPage(): Page = listPage("Movies") {
        entryGroups(repo.activePlaylist?.movies.orEmpty()) { entries, g ->
            posterGridPage(g, entries) { e -> play(e.url, e.name) }
        }
    }

    // -------------------------------------------------------------- shows ---
    private fun showGroupsPage(): Page = listPage("TV Shows") {
        entryGroups(repo.activePlaylist?.series.orEmpty()) { entries, g ->
            posterGridPage(g, entries) { e -> show(episodesPage(e)) }
        }
    }

    /**
     * Plex-style poster grid for a movie/series group. Only the title lists use
     * it — group and channel lists stay plain rows because that is what makes
     * the legacy edition feel fast from the wheel.
     */
    private fun posterGridPage(title: String, entries: List<Entry>, onOpen: (Entry) -> Unit): Page =
        Page(title) {
            val w = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            val cols = (w / 150f).toInt().coerceIn(2, 7)
            val grid = GridView(this).apply {
                numColumns = cols
                horizontalSpacing = dp(10)
                verticalSpacing = dp(6)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                setPadding(dp(14), dp(4), dp(14), dp(12))
                clipToPadding = false
                adapter = PosterAdapter(entries, onOpen)
                setOnItemClickListener { _, _, pos, _ -> onOpen(entries[pos]) }
            }
            column(title) {
                addView(grid, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }

    private inner class PosterAdapter(
        private val entries: List<Entry>,
        private val onOpen: (Entry) -> Unit,
    ) : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? PosterCell) ?: PosterCell(this@LegacyCarActivity).apply {
                isClickable = true
                isFocusable = true
            }
            val e = entries[position]
            cell.bind(e, Palette.HUES[position % Palette.HUES.size])
            cell.setOnClickListener { onOpen(e) }
            return cell
        }
    }

    private fun entryGroups(entries: List<Entry>, pageFor: (List<Entry>, String) -> Page): List<Row> {
        if (entries.isEmpty()) {
            return listOf(Row("Nothing here yet", "Load an Xtream source from the phone app") {})
        }
        val pl = repo.activePlaylist
        val hidden = if (pl != null) repo.hiddenGroups(pl.name) else emptySet()
        return entries.filterNot { it.group in hidden }
            .groupBy { it.group.ifBlank { "Ungrouped" } }.toSortedMap()
            .map { (g, es) -> Row(g, "${es.size} titles") { show(pageFor(es, g)) } }
    }

    private fun episodesPage(series: Entry): Page {
        lateinit var page: Page
        page = Page(series.name) {
            scope.launch {
                val eps = withContext(Dispatchers.IO) {
                    runCatching { repo.episodesFor(series) }.getOrDefault(emptyList())
                }
                if (current === page) {
                    val rows = eps.map { ep ->
                        Row(ep.displayTitle(), null) {
                            play(ep.url, "${series.name} — ${ep.displayTitle()}")
                        }
                    }
                    root.removeAllViews()
                    root.addView(buildList(series.name, rows), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }
            column(series.name) {
                addView(text("Loading episodes…", 14f, Palette.MUTED, false))
            }
        }
        return page
    }

    // -------------------------------------------------------------- video ---
    private fun videoPage(title: String): Page = Page(title) { buildVideo(title) }

    private fun buildVideo(title: String): View {
        // TextureView, not SurfaceView: a SurfaceView punches its own window
        // layer, so any control drawn "on top" of it is buried under the video.
        // A TextureView lives in the normal hierarchy, which is what lets the
        // controls float over a full-window picture.
        val host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this)
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val surface = Surface(st)
                val s = svc
                if (s != null) s.setVideoSurface(surface) else { pendingSurface = surface; bindPlayback() }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                pendingSurface = null
                runCatching { svc?.setVideoSurface(null) }
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        host.addView(video, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // transport overlay: hidden by default, tap the picture to toggle, fades
        // itself out again after a few seconds so the video stays full screen.
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(205, 0x14, 0x14, 0x1C))
            setPadding(dp(4), dp(3), dp(8), dp(3))
        }
        strip.addView(text("‹  Back", 13.5f, Palette.ACCENTS[0], true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(6), dp(14), dp(6))
            setOnClickListener { back() }
        })

        val playPause = text("", 13.5f, Palette.TEXT, true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        fun refreshPlayLabel() {
            playPause.text = if (svc?.isPlaying() == true) "❚❚  Pause" else "▶  Play"
        }
        refreshPlayLabel()
        playPause.setOnClickListener {
            val s = svc ?: return@setOnClickListener
            if (s.isPlaying()) s.pause() else s.playNow()
            refreshPlayLabel()
            // The player flips its own state a beat later; settle the label after it.
            handler.postDelayed({ refreshPlayLabel() }, 350L)
        }
        strip.addView(playPause)
        strip.addView(text("■  Stop", 13.5f, Palette.TEXT, true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener {
                runCatching { svc?.pause() }
                back()
            }
        })
        strip.addView(text(title, 13.5f, Palette.MUTED, true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        host.addView(strip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val hideStrip = Runnable { strip.visibility = View.GONE }
        fun showStrip(thenHideMs: Long) {
            handler.removeCallbacks(hideStrip)
            strip.visibility = View.VISIBLE
            if (thenHideMs > 0) handler.postDelayed(hideStrip, thenHideMs)
        }
        // Brief flash on entry so the controls are discoverable, then the picture
        // is untouched until the driver taps it.
        showStrip(3500L)
        video.setOnClickListener {
            if (strip.visibility == View.VISIBLE) {
                handler.removeCallbacks(hideStrip)
                strip.visibility = View.GONE
            } else showStrip(4000L)
        }
        strip.setOnClickListener { showStrip(4000L) }

        // No AA app header over the picture: the picture owns the whole window.
        runCatching { carUiController.statusBarController.hideAppHeader() }

        return host
    }

    // ---------------------------------------------------------------- web ---
    private fun webPage(title: String, url: String, searchBase: String? = null): Page = Page(title) {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(url)
        }
        // Full-bleed: no "‹ Back | title" header row eating the top. The page
        // owns the window; Back comes from the car's back key (onBackPressed),
        // which also drops the keyboard. Searchable pages keep ONE slim row for
        // the bar, because that bar is the only route to the car keyboard.
        val content: View = if (searchBase == null) {
            web
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                addView(searchBar(web, searchBase), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(web, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
        // The host does not deliver the car's back key to a hosted activity, so a
        // full-bleed page needs its own way out. Small floating chip, bottom-right,
        // over the page: costs no layout row and stays clear of Google's own UI.
        FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(text("‹  Back", 13f, Palette.TEXT, true).apply {
                isClickable = true
                isFocusable = true
                setBackgroundColor(Color.argb(190, 0x16, 0x16, 0x20))
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { back() }
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(14), dp(14))
            })
        }
    }

    /**
     * A Google search bar the CAR keyboard can type into. A projected WebView
     * cannot take the head-unit IME (the host binds it to a [CarEditable] we
     * own), so this is a [CarRestrictedEditText]: typing here raises the car
     * keyboard, Go hands the query to Google in the WebView below.
     */
    private fun searchBar(web: WebView, searchBase: String): View {
        val field = CarRestrictedEditText(this, null).apply {
            hint = "Search Google"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.DIM)
            setBackgroundColor(Palette.PANEL)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        fun submit() {
            val q = field.text.toString().trim()
            if (q.isEmpty()) return
            dropKeyboard()
            field.clearFocus()
            web.loadUrl(searchBase + Uri.encode(q))
            Log.i(TAG, "web search: $q")
        }
        // Proof the host's keystrokes actually land, read back from logcat.
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                Log.i(TAG, "field text: " + e)
            }
        })
        // ONE explicit request per tap. A focus listener here re-raised the
        // keyboard every time the host took focus, which fights the host in a
        // loop: keyboard up -> host focus -> focus lost -> keyboard up again.
        field.setOnClickListener { raiseKeyboard(field) }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submit(); true
            } else false
        }

        val go = text("▶  Go", 14f, Palette.ACCENTS[0], true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { submit() }
        }
        // The car keyboard has no dismiss affordance of its own in this host, so
        // give it one: dropping the input session is what takes it off screen.
        val hide = text("✕  Hide", 14f, Palette.MUTED, true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                dropKeyboard()
                field.clearFocus()
                Log.i(TAG, "keyboard: hidden by button")
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(8), dp(8), dp(8), dp(4))
            addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(hide, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
            addView(go, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        }
        // Tapping anywhere on the bar raises the keyboard, not just the box.
        bar.setOnClickListener { raiseKeyboard(field) }
        bar.post {
            Log.i(TAG, "search bar: bar=" + bar.width + "x" + bar.height +
                " field=" + field.width + "x" + field.height + " @density" + resources.displayMetrics.density)
        }
        field.setOnTouchListener { _, e ->
            Log.i(TAG, "field touch action=" + e.action + " @" + e.x.toInt() + "," + e.y.toInt())
            if (e.action == android.view.MotionEvent.ACTION_UP) raiseKeyboard(field)
            false // let the EditText keep its own caret/selection handling
        }
        return bar
    }



    /** Focus the field and ask the host to put its keyboard up for it. */
    private fun raiseKeyboard(field: CarRestrictedEditText) {
        Log.i(TAG, "raiseKeyboard (requested=" + keyboardRequested + ")")
        if (keyboardRequested) return
        field.requestFocus()
        val input = hostInput
        if (input == null) {
            Log.w(TAG, "no host InputManager — cannot raise the car keyboard")
            return
        }
        runCatching { CarImeBridge.start(input, field) }
            .onSuccess {
                keyboardRequested = true
                Log.i(TAG, "keyboard: startInput requested")
            }
            .onFailure { Log.w(TAG, "keyboard: startInput failed", it) }
    }

    private fun dropKeyboard() {
        val input = hostInput ?: return
        runCatching { CarImeBridge.stop(input) }
        if (keyboardRequested) Log.i(TAG, "keyboard: stopInput (active=${CarImeBridge.isActive(input)})")
        keyboardRequested = false
    }

    // ------------------------------------------------------------- helpers ---
    private fun text(s: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun favourites(): Set<String> =
        getSharedPreferences("mytv_favorites", Context.MODE_PRIVATE)
            .getStringSet("ids", emptySet())?.toSet().orEmpty()

    private companion object {
        const val TAG = "RogueLegacyCar"
        const val GOOGLE_SEARCH = "https://www.google.com/search?q="
        const val YOUTUBE_SEARCH = "https://m.youtube.com/results?search_query="
        /** Guide preview pane on/off, remembered per unit. */
        const val KEY_PREVIEW = "preview_on"
    }
}

/** Same shape as the modern car browse screens' private helper. */
private fun Episode.displayTitle(): String =
    if (title.isNotBlank()) title
    else if (number > 0) "Episode $number"
    else "Episode"
