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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.support.car.input.CarRestrictedEditText
import coil.load
import com.google.android.apps.auto.sdk.CarImeBridge
import com.google.android.apps.auto.sdk.CarActivity
import com.rogueplayer.MyApplication
import com.rogueplayer.R
import com.rogueplayer.data.Channel
import com.rogueplayer.data.Entry
import com.rogueplayer.data.EpgProgram
import com.rogueplayer.data.Episode
import com.rogueplayer.data.PlaylistKind
import com.rogueplayer.data.ScoreGame
import com.rogueplayer.data.ScoreSource
import com.rogueplayer.player.PlaybackService
import com.rogueplayer.ui.EpgGridView
import com.rogueplayer.ui.Palette
import com.rogueplayer.ui.PosterCell
import com.rogueplayer.ui.SplashView
import com.rogueplayer.ui.TeamBadge
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
    /** The ListView adapter on screen, so EPG arriving later can refresh the rows. */
    private var rowAdapter: RowAdapter? = null
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
    /** The surface the current rail's own TextureView created — what it may detach. */
    private var railSurface: Surface? = null
    /** Guide scroll offset, kept across the page rebuilds that every return causes. */
    private var guideScrollX = 0f
    private var guideScrollY = 0f
    private var guideScrollValid = false
    private var previewName: TextView? = null
    private var previewNow: TextView? = null
    private var previewNext: TextView? = null
    private var previewTrack: View? = null
    private var previewFill: View? = null
    private var previewHint: TextView? = null
    /** Last channel played (from the rail or a programme block) — what the guide
     *  restores into the rail when you come back from full screen. */
    private var lastPlayed: Channel? = null
    /** Set by back() when the page we are returning to is the guide, so the guide
     *  re-starts the channel in its rail instead of un-pausing a torn-down player. */
    private var resumePreviewOnReturn = false
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

    /** Full-screen play of a known channel, remembered so the guide can put it
     *  back into its preview rail afterwards. */
    private fun playChannel(ch: Channel) {
        lastPlayed = ch
        play(ch.url, ch.name)
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
        // The score poller re-arms itself from the scores page build only.
        handler.removeCallbacks(scoreTicker)
        if (pushCurrent) current?.let { backStack.addLast(it) }
        current = page
        root.removeAllViews()
        root.addView(page.build(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun back() {
        val prev = backStack.removeLastOrNull()
        // Returning from full screen into the guide: re-select the channel on the
        // way back so the rail starts its own stream, instead of trying to un-pause
        // a player whose surface the video page just tore down.
        resumePreviewOnReturn = prev != null && prev.title == "TV Guide" &&
            svc?.currentTitle?.isNotBlank() == true
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
        // Watchdog: a hosted car Activity can sit created-but-not-shown for seconds.
        // If not one frame has painted, don't burn the animation into the void —
        // go straight to the landing (and say so), instead of the owner seeing the
        // boot "sometimes show, sometimes not".
        handler.postDelayed({
            if (!bootFinished && sv.paintedFrames == 0) {
                Log.i(TAG, "boot: no frame painted in 3s — skipping straight to landing")
                bootFinished = true
                show(landingPage(), pushCurrent = false)
            }
        }, 3_000L)
        sv.start {
            bootFinished = true
            Log.i(TAG, "boot: done -> landing (painted ${sv.paintedFrames} frames)")
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
        // Search sits in the MIDDLE of the tile grid, not under it. The bottom of
        // a vertically-centred column is the first thing a short landscape head
        // unit clips, which is how the old bottom pill went missing in the car
        // while it still showed on the DHU. Scores shares that middle row.
        val search = Tile("Search", R.drawable.ic_nav_search, 0) {
            show(webPage("Google", "https://www.google.com", GOOGLE_SEARCH))
        }
        val tiles = listOf(
            Tile("Live TV", R.drawable.ic_nav_live, 0) { show(liveGroupsPage()) },
            Tile("Movies", R.drawable.ic_nav_movies, 0) { show(movieGroupsPage()) },
            Tile("Scores", R.drawable.ic_nav_sports, 1) { show(scoresPage()) },
            search,
            Tile("TV Shows", R.drawable.ic_nav_tv, 1) { show(showGroupsPage()) },
            Tile("YouTube", R.drawable.ic_nav_youtube, 1) {
                show(webPage("YouTube", "https://m.youtube.com", YOUTUBE_SEARCH))
            },
        )
        // Live/Movies · Scores/Search · Shows/YouTube — six tiles, three rows.
        val rows = tiles.chunked(2)
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
    private class Row(
        val title: String,
        val subtitle: String?,
        val channel: Channel? = null,
        val badge: String? = null,
        val onClick: () -> Unit,
    )

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
            adapter = RowAdapter(all).also { rowAdapter = it }
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
            // Capsule row: rounded CARD pill on the BG, with a circular initial tile and a
            // thin guide line. Pills here, but our own palette and our own layout.
            val pill = LinearLayout(this@LegacyCarActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = capsule(Palette.CARD, 26f)
            }
            pill.addView(initialsTile(row.title))
            val col = LinearLayout(this@LegacyCarActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(row.title, 15f, Palette.TEXT, true).apply {
                gravity = Gravity.START
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.subtitle?.let {
                col.addView(text(it, 11.5f, Palette.MUTED, false).apply {
                    gravity = Gravity.START
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            // The guide line and its progress bar, as on the phone's pills: what is on now,
            // when it ends, and how far through it we are.
            row.channel?.let { ch ->
                val guide = guideLineFor(ch)
                col.addView(text(guide.first, 11.5f, Palette.MUTED, false).apply {
                    gravity = Gravity.START
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) })
                val frac = guide.second
                val track = LinearLayout(this@LegacyCarActivity).apply { orientation = LinearLayout.HORIZONTAL }
                track.addView(View(this@LegacyCarActivity).apply {
                    setBackgroundColor(if (frac > 0f) Palette.ACCENTS[0] else Palette.LINE)
                }, LinearLayout.LayoutParams(0, dp(3), frac.coerceAtLeast(0.02f)))
                track.addView(View(this@LegacyCarActivity).apply { setBackgroundColor(Palette.LINE) },
                    LinearLayout.LayoutParams(0, dp(3), (1f - frac).coerceAtLeast(0.02f)))
                col.addView(track, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(5) })
            }
            pill.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(12) })
            row.badge?.let { b ->
                pill.addView(text(b, 12f, Palette.ACCENTS[0], true).apply {
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    background = capsule(tint(Palette.ACCENTS[0], 0x26), 20f)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) })
            }
            v.addView(pill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        // Accent-led hairline under the header — the "line" half of the look.
        val rule = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rule.addView(View(this).apply { setBackgroundColor(Palette.ACCENTS[0]) },
            LinearLayout.LayoutParams(dp(46), dp(2)))
        rule.addView(View(this).apply { setBackgroundColor(Palette.LINE) },
            LinearLayout.LayoutParams(0, dp(2), 1f))
        col.addView(rule, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fill()
        }
        col.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    // ------------------------------------------------------------ scores ---
    /**
     * Scores page: the same slate the phone's Sports tab draws, as game cards.
     *
     * Owner's rules carried over unchanged: live games first, league chips filter the slate,
     * and a tap resolves the game's broadcast to a channel in the playlist — one match plays,
     * several open a picker, none says so on the status line (a car has no toast to rely on).
     *
     * The slate itself comes from the shared [ScoreSource]; nothing here talks to ESPN.
     */
    private var scoreSlate: List<ScoreGame> = emptyList()
    private var scoreLeague = "ALL"
    private var scoreFetchAt = 0L
    private var scoreFetching = false
    private var scoreStatus: TextView? = null
    private var scoreChipsRow: LinearLayout? = null
    private var scoreAdapter: ScoreAdapter? = null

    /** Live games move, so re-poll while the scores page is the page on screen. 60s matches
     *  ScoreSource's own cache window — polling faster only ever returns the cache. */
    private val scoreTicker = object : Runnable {
        override fun run() {
            if (current?.title != "SCORES") return
            loadScores(force = true)
            handler.postDelayed(this, 60_000L)
        }
    }

    /** Helmet hues for score cards: eight far-apart colours (the guide's HUES are too alike
     *  side by side). Same set the phone uses. */
    private val SCORE_HUES = intArrayOf(
        0xFFFF9F1C.toInt(), 0xFF4EA8DE.toInt(), 0xFF9B5DE5.toInt(), 0xFF2EC4B6.toInt(),
        0xFFE86A92.toInt(), 0xFFB5C94B.toInt(), 0xFFF2C14E.toInt(), 0xFF8C7BFF.toInt(),
    )
    private fun scoreHue(name: String) =
        SCORE_HUES[(name.hashCode() and 0x7fffffff) % SCORE_HUES.size]

    private fun scoreHues(g: ScoreGame): Pair<Int, Int> {
        val away = scoreHue(g.away)
        val raw = scoreHue(g.home)
        val home = if (raw == away) SCORE_HUES[(SCORE_HUES.indexOf(away) + 3) % SCORE_HUES.size]
        else raw
        return away to home
    }

    private fun scoresPage(): Page = Page("SCORES") { buildScores() }

    private fun buildScores(): View {
        val view = column("SCORES", HeaderAction("⟳") { loadScores(force = true) }) {
            // A stream may be running from the guide or a channel list; the car has no
            // other route back to the picture from here.
            svc?.currentTitle?.takeIf { it.isNotBlank() }?.let { t ->
                addView(pill("▶  Now playing · $t") { show(videoPage(t)) }.apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
                })
            }
            addView(buildScoreChips())
            val status = text("", 11f, Palette.MUTED, false).apply {
                gravity = Gravity.START
                setPadding(dp(20), dp(6), dp(20), dp(2))
                maxLines = 2
            }
            scoreStatus = status
            addView(status)
            val list = ListView(this@LegacyCarActivity).apply {
                adapter = ScoreAdapter(scoreFiltered()).also { scoreAdapter = it }
                divider = android.graphics.drawable.ColorDrawable(Color.argb(28, 255, 255, 255))
                dividerHeight = 1
                cacheColorHint = Color.TRANSPARENT
                setPadding(dp(8), dp(2), dp(8), dp(8))
                clipToPadding = false
            }
            addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        updateScoreStatus()
        loadScores(force = false)
        handler.removeCallbacks(scoreTicker)
        handler.postDelayed(scoreTicker, 60_000L)
        return view
    }

    private fun scoreFiltered(): List<ScoreGame> =
        if (scoreLeague == "ALL") scoreSlate else scoreSlate.filter { it.league == scoreLeague }

    private val SCORE_LEAGUES = listOf("NFL", "NCAAF", "NBA", "NCAAB", "MLB", "NHL")

    /** Only leagues that actually have games — an empty chip is a dead end on a head unit. */
    private fun scoreLeagues(): List<String> {
        if (scoreSlate.isEmpty()) return SCORE_LEAGUES
        return SCORE_LEAGUES.filter { l -> scoreSlate.any { it.league == l } }
    }

    private fun buildScoreChips(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(6), dp(16), dp(2))
        }
        scoreChipsRow = strip
        fillScoreChips(strip)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    private fun fillScoreChips(strip: LinearLayout) {
        strip.removeAllViews()
        val entries = ArrayList<Pair<String, Int>>()
        entries += "ALL" to scoreSlate.size
        scoreLeagues().forEach { l -> entries += l to scoreSlate.count { it.league == l } }
        entries.forEach { (league, count) ->
            val on = league == scoreLeague
            strip.addView(text(
                if (count > 0) "$league  $count" else league,
                12.5f,
                if (on) 0xFF0B0B10.toInt() else Palette.MUTED,
                true,
            ).apply {
                isClickable = true
                isFocusable = true
                setPadding(dp(13), dp(7), dp(13), dp(7))
                background = capsule(if (on) Palette.ACCENTS[0] else Palette.CARD, 18f)
                setOnClickListener {
                    scoreLeague = league
                    scoreAdapter?.let { a ->
                        a.games = scoreFiltered()
                        a.notifyDataSetChanged()
                    }
                    fillScoreChips(strip)
                    updateScoreStatus()
                    Log.i(TAG, "scores: league -> $scoreLeague (${scoreFiltered().size} games)")
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun updateScoreStatus() {
        val st = scoreStatus ?: return
        val games = scoreFiltered()
        st.setTextColor(Palette.MUTED)
        st.text = when {
            scoreFetching && scoreSlate.isEmpty() -> "Fetching the slate…"
            scoreSlate.isEmpty() -> "No scores right now — ESPN unreachable, or no games today."
            games.isEmpty() -> "No $scoreLeague games on the slate."
            else -> buildString {
                append(if (games.size == 1) "1 game" else "${games.size} games")
                val live = games.count { it.live }
                if (live > 0) append(" · $live live")
                append(" · ESPN")
            }
        }
    }

    private fun loadScores(force: Boolean) {
        if (scoreFetching) return
        val now = System.currentTimeMillis()
        if (!force && scoreSlate.isNotEmpty() && now - scoreFetchAt < 30_000L) return
        scoreFetching = true
        scoreFetchAt = now
        updateScoreStatus()
        scope.launch {
            val slate = withContext(Dispatchers.IO) {
                runCatching { ScoreSource.slate() }.getOrDefault(emptyList())
            }
            scoreFetching = false
            if (slate.isNotEmpty()) scoreSlate = slate
            // A league with nothing on today must not leave the page filtered to nothing.
            if (scoreLeague != "ALL" && scoreSlate.none { it.league == scoreLeague }) {
                scoreLeague = "ALL"
            }
            Log.i(
                TAG, "scores: ${slate.size} games, ${slate.count { it.live }} live, " +
                    "leagues=${slate.map { it.league }.distinct()}",
            )
            if (current?.title != "SCORES") return@launch
            scoreAdapter?.let { a ->
                a.games = scoreFiltered()
                a.notifyDataSetChanged()
            }
            scoreChipsRow?.let { fillScoreChips(it) }
            updateScoreStatus()
        }
    }

    /** One line of feedback in the header area (no toasts in a hosted car activity). */
    private fun sayScore(msg: String) {
        val st = scoreStatus ?: return
        st.setTextColor(Palette.ACCENTS[0])
        st.text = msg
        st.postDelayed({
            if (st === scoreStatus) updateScoreStatus()
        }, 6_000L)
    }

    private inner class ScoreAdapter(var games: List<ScoreGame>) : BaseAdapter() {
        override fun getCount() = games.size
        override fun getItem(position: Int) = games[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@LegacyCarActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(3), dp(4), dp(3))
            }
            row.removeAllViews()
            bindGameCard(row, games[position])
            return row
        }
    }

    private fun bindGameCard(row: LinearLayout, g: ScoreGame) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = capsule(Palette.CARD, 16f)
            val pad = if (g.completed) dp(7) else dp(9)
            setPadding(dp(12), pad, dp(12), pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { openGame(g) }
        }
        if (g.completed) buildFinalLine(card, g) else buildLiveCard(card, g)
        row.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun broadcastPill(bc: String) = text(bc, 10.5f, Palette.ACCENTS[0], true).apply {
        setPadding(dp(9), dp(3), dp(9), dp(3))
        background = capsule(tint(Palette.ACCENTS[0], 0x26), 20f)
    }

    private fun buildLiveCard(card: LinearLayout, g: ScoreGame) {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(if (g.live) "● LIVE" else g.status, 10.5f,
            if (g.live) 0xFFFF6B6B.toInt() else Palette.TEXT, true).apply {
            gravity = Gravity.START
        })
        g.detail?.let { d ->
            top.addView(text("  $d", 10.5f, Palette.MUTED, true).apply { gravity = Gravity.START })
        }
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        g.broadcasts.firstOrNull()?.let { top.addView(broadcastPill(it)) }
        card.addView(top)

        val (awayHue, homeHue) = scoreHues(g)
        card.addView(teamLine(g.away, g.awayAbbr, g.awayScore, g, true, awayHue))
        card.addView(teamLine(g.home, g.homeAbbr, g.homeScore, g, false, homeHue))
        card.addView(text(
            listOfNotNull(g.league, g.note?.takeIf { it.length < 34 }).joinToString(" · "),
            10f, Palette.DIM, false,
        ).apply {
            gravity = Gravity.START
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun teamLine(
        name: String,
        abbr: String,
        score: String?,
        g: ScoreGame,
        isAway: Boolean,
        hue: Int,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(teamMark(name, abbr, hue, g.league,
            if (isAway) g.awayLogo else g.homeLogo, 26))
        row.addView(text(name, 14f, Palette.TEXT, true).apply {
            gravity = Gravity.START
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(10) })
        val other = if (isAway) g.homeScore else g.awayScore
        val leading = score != null && other != null &&
            (score.toIntOrNull() ?: 0) > (other.toIntOrNull() ?: 0)
        row.addView(text(
            score ?: "–", 18f,
            if (leading || (!g.live && !g.completed)) Palette.TEXT else Palette.DIM, true,
        ))
        return row
    }

    /** FINAL card collapses to one line, so a finished slate scans like results. */
    private fun buildFinalLine(card: LinearLayout, g: ScoreGame) {
        val (awayHue, homeHue) = scoreHues(g)
        // Hockey and baseball badges are shapes, not letter tiles, so their
        // abbreviations sit beside the score.
        val shaped = g.league == "NHL" || g.league == "MLB"
        fun tag(s: String) = text(s, 10.5f, Palette.MUTED, true).apply {
            setPadding(dp(6), 0, dp(2), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(teamMark(g.away, g.awayAbbr, awayHue, g.league, g.awayLogo, 26))
        if (shaped) row.addView(tag(g.awayAbbr.take(3)))
        row.addView(text(g.awayScore ?: "–", 17f, Palette.TEXT, true).apply {
            setPadding(if (shaped) dp(4) else dp(8), 0, 0, 0)
        })
        row.addView(text("–", 14f, Palette.DIM, true).apply { setPadding(dp(4), 0, dp(4), 0) })
        row.addView(text(g.homeScore ?: "–", 17f, Palette.DIM, true))
        if (shaped) row.addView(tag(g.homeAbbr.take(3)))
        row.addView(teamMark(g.home, g.homeAbbr, homeHue, g.league, g.homeLogo, 26).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(text("FINAL", 9.5f, Palette.DIM, true))
        g.broadcasts.firstOrNull()?.let { bc ->
            row.addView(broadcastPill(bc), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) })
        }
        card.addView(row)
    }

    /**
     * Team mark: the ESPN logo when it loads, our own [TeamBadge] underneath when it does
     * not. Nothing is drawn if both fail, so a card never shows a broken image box.
     */
    private fun teamMark(
        name: String,
        abbr: String,
        hue: Int,
        league: String,
        logoUrl: String?,
        sizeDp: Int,
    ): View {
        val holder = FrameLayout(this)
        val badge = TeamBadge(this, abbr.take(3).ifBlank { name.take(2) }, hue, league)
        holder.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (!logoUrl.isNullOrBlank()) {
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            holder.addView(img, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            img.load(logoUrl) {
                listener(
                    onSuccess = { _, _ -> badge.visibility = View.GONE },
                    onError = { _, _ -> holder.removeView(img) },
                )
            }
        }
        holder.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return holder
    }

    /**
     * Broadcast → channel. One match plays, several open a picker page, none says so: never
     * open a stream that has nothing to do with the game.
     */
    private fun openGame(g: ScoreGame) {
        val channels = repo.activePlaylist?.channels.orEmpty()
        if (channels.isEmpty()) {
            sayScore("Load a source first")
            return
        }
        val hits = channels.filter { ch ->
            g.broadcasts.any { b -> ch.name.uppercase().contains(b.uppercase()) }
        }
        Log.i(TAG, "score tap: ${g.away} at ${g.home} ${g.broadcasts} -> ${hits.size} channels")
        when {
            hits.size == 1 -> playChannel(hits[0])
            hits.isEmpty() -> sayScore(
                (g.broadcasts.firstOrNull()?.let { "$it is not in your playlist" }
                    ?: "No broadcast listed for this game"),
            )
            else -> show(scorePickerPage(g, hits))
        }
    }

    private fun scorePickerPage(g: ScoreGame, hits: List<Channel>): Page =
        listPage("${g.awayAbbr.ifBlank { g.away }} at ${g.homeAbbr.ifBlank { g.home }}") {
            hits.take(20).map { ch ->
                Row(ch.name, ch.group.ifBlank { "Live TV" }, channel = ch) { playChannel(ch) }
            }
        }


    // ---------------------------------------------------------- live tv ---
    private fun liveGroupsPage(): Page = listPage(
        "Live TV",
        HeaderAction("Guide ▸") { openGuide(null) },
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
                out.add(Row("★ Favourites", "${favs.size} channels", badge = "${favs.size}") {
                    show(channelsPage("Favourites", favs))
                })
            }
            shown.groupBy { it.group.ifBlank { "Ungrouped" } }.toSortedMap().forEach { (g, chs) ->
                out.add(Row(g, "${chs.size} channels", badge = "${chs.size}") { show(channelsPage(g, chs)) })
            }
            out
        }
    }

    private fun channelsPage(title: String, channels: List<Channel>): Page =
        listPage(
            title,
            HeaderAction("Guide ▸") { openGuide(channels) },
        ) {
            ensureRowGuide(channels)
            channels.map { ch -> Row(ch.name, ch.group, ch) { playChannel(ch) } }
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
    /**
     * Open the guide as a new page: it starts at "now" and at the top, because the
     * scoped list (all channels vs one category) is a different set of rows. Returns
     * from full screen do NOT come through here — they rebuild the page that was
     * already open and keep its offset.
     */
    private fun openGuide(scoped: List<Channel>?) {
        guideScrollValid = false
        show(guidePage(scoped))
    }

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
            // This page is rebuilt from scratch on every return from full screen. Put the
            // row highlight back on the channel the rail is holding, and keep the scroll
            // offset so grid.post can restore it once the view has been measured.
            selectedChannelId = previewChannel?.id
            onScrollMoved = { x, y ->
                guideScrollX = x
                guideScrollY = y
                guideScrollValid = true
            }
            onChannelClick = { ch ->
                // First tap on a channel: show it in the rail. A tap on the channel that
                // is ALREADY in the rail: go full screen — the same as tapping the rail
                // picture. Rows used to be select-only, so a second tap just re-tuned the
                // rail and the only way to full screen was a programme block, which is
                // hard to hit. With Preview off there is no rail to preview in, so a tap
                // goes straight to full screen.
                //
                // The check is "is it in the rail", not "is this row selected": the row
                // selection lives on the grid view, which is rebuilt whenever the page is
                // rebuilt (e.g. coming back from full screen), so keying off it made the
                // tap after a return re-tune the same channel instead of enlarging it.
                if (!previewOn || previewChannel?.id == ch.id) {
                        playChannel(ch)
                } else {
                    selectedChannelId = ch.id
                    invalidate()
                    startPreview(ch)
                }
            }
            // One tap on a block plays it full screen; the gutter previews first, then
            // promotes the same channel to full screen on the second tap.
            onProgramClick = { ch, _ -> playChannel(ch) }
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
            // A rebuilt page (return from full screen, preview toggled) keeps the offset
            // it had — landing back at the top of the list on every return was the
            // complaint. Only a freshly opened guide starts at "now".
            if (guideScrollValid) {
                grid.restoreScroll(guideScrollX, guideScrollY)
            } else {
                grid.scrollToNow()
            }
            loadGuide(grid)
        }
        grid.postDelayed({ if (guideGrid === grid) grid.invalidate() }, 250L)

        // Coming back from full screen (or re-entering the guide): the stream is
        // still running but its surface died with the previous page, and detaching
        // pauses the player — so put it back on THIS rail's TextureView. The resume
        // is also re-run a beat later: the old TextureView's destroy callback lands
        // asynchronously and would otherwise clear the surface right after we set it.
        // Only a channel of this guide may be restored into the rail. If a movie or
        // episode is what's playing (or anything else), the guide leaves playback
        // strictly alone — VOD is never restarted by opening the guide.
        val playing = svc?.currentTitle
        val resume = (previewChannel ?: lastPlayed)?.takeIf { ch ->
            playing != null && playing.isNotBlank() && playing == ch.name
        }
        if (previewOn && resume != null) {
            if (resumePreviewOnReturn) {
                resumePreviewOnReturn = false
                // Came back from full screen: the stream is still the same one, only its
                // surface died with the video page. Re-point it at this rail and un-pause
                // if the detach managed to pause it — never re-tune (a fresh play() would
                // drop the live connection) and never leave it paused, which is what made
                // the owner press ▶ Play after every trip into full screen.
                previewChannel = resume
                previewName?.text = resume.name
                previewHint?.visibility = View.GONE
                attachPreviewSurface()
                if (svc?.isPlaying() != true) runCatching { svc?.playNow() }
                grid.postDelayed({ if (guideOpen) attachPreviewSurface() }, 400L)
            } else if (svc?.currentTitle?.isNotBlank() == true) {
                previewChannel = resume
                previewName?.text = resume.name
                previewHint?.visibility = View.GONE
                attachPreviewSurface()
                grid.postDelayed({ if (guideOpen) attachPreviewSurface() }, 400L)
            }
        } else if (resumePreviewOnReturn) {
            // Nothing of ours is playing any more — don't leave a stale flag behind.
            resumePreviewOnReturn = false
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
        Log.i(TAG, "guide rail: ${railDp.toInt()}dp wide, card ${cardW.toInt()}x${cardH.toInt()}")
        val card = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#111A2B")) }
        val tv = TextureView(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val mine = Surface(st)
                railSurface = mine
                previewSurface = mine
                attachPreviewSurface()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // This rail's texture dies when the page is replaced — that callback can
                // land after the next page (video, or a fresh guide rail) already owns the
                // surface, so only detach if the attached surface is still ours.
                val mine = railSurface
                railSurface = null
                if (previewSurface === mine) previewSurface = null
                runCatching { svc?.detachVideoSurface(mine) }
                runCatching { mine?.release() }
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
        // Tap the picture to watch it full screen — the rail is a preview, not the
        // show. The children (TextureView / hint) would otherwise swallow the tap.
        card.isClickable = true
        // Touch probe: tells us whether a finger on the picture reaches this view at
        // all, versus reaching it and being swallowed.
        card.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                Log.i(TAG, "preview card ACTION_DOWN")
            }
            false
        }
        card.setOnClickListener {
            Log.i(TAG, "preview card tapped (channel=${previewChannel?.name})")
            previewChannel?.let { playChannel(it) }
        }
        tv.setOnClickListener {
            Log.i(TAG, "preview surface tapped")
            card.performClick()
        }
        previewHint?.setOnClickListener {
            Log.i(TAG, "preview hint tapped")
            card.performClick()
        }
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
        lastPlayed = ch
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
        // The surface this view created, held so its own destroy callback can only
        // detach that one. The destroy callback lands asynchronously and can arrive
        // after the next page has attached its surface; detaching unconditionally
        // there paused the stream the new page had just started.
        var ownedSurface: Surface? = null
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val surface = Surface(st)
                ownedSurface = surface
                val s = svc
                if (s != null) s.setVideoSurface(surface) else { pendingSurface = surface; bindPlayback() }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                val mine = ownedSurface
                ownedSurface = null
                if (pendingSurface === mine) pendingSurface = null
                runCatching { svc?.detachVideoSurface(mine) }
                runCatching { mine?.release() }
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
        // A/V offset: the car's audio path and its video path buffer differently, so the
        // amount of correction depends on the head unit. Dial it here, on the picture.
        val syncLabel = text("", 12.5f, Palette.MUTED, true).apply {
            setPadding(dp(10), dp(6), dp(2), dp(6))
        }
        fun refreshSync() {
            val ms = svc?.audioDelayMs ?: 0
            syncLabel.text = "Sync ${ms}ms"
            syncLabel.setTextColor(if (ms == 0) Palette.MUTED else Palette.ACCENTS[0])
        }
        fun nudgeSync(delta: Int) {
            val s = svc ?: return
            s.setAudioDelay((s.audioDelayMs + delta).coerceIn(0, 400))
            refreshSync()
            Log.i(TAG, "audio delay now ${s.audioDelayMs}ms")
        }
        strip.addView(text("A−", 13.5f, Palette.TEXT, true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener { nudgeSync(-40) }
        })
        strip.addView(syncLabel.apply {
            isClickable = true
            setOnClickListener { nudgeSync(40) }
        })
        strip.addView(text("A+", 13.5f, Palette.TEXT, true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(6), dp(6), dp(10), dp(6))
            setOnClickListener { nudgeSync(40) }
        })
        refreshSync()
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

    /** Capsule background in the Rogue palette. */
    private fun capsule(color: Int, radiusDp: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    /** [color] with [alpha] (0..255) in the top byte. */
    private fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** Circular initial tile — a logo stand-in tinted from our own hues. */
    private fun initialsTile(name: String): TextView {
        val words = name.split(" ", "|", "-", "_").filter { it.isNotBlank() }
        val initials = words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("").ifBlank { "?" }
        val hue = Palette.HUES[(name.hashCode() and 0x7fffffff) % Palette.HUES.size]
        return TextView(this).apply {
            text = initials
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0B0B10.toInt())
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(hue)
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
    }

    /**
     * "22:00 – 22:30 · Echo Umbra" plus the fraction through it. Reads the guide cache the
     * guide page fills, and the small fetches [ensureRowGuide] makes for list rows.
     */
    private fun guideLineFor(ch: Channel): Pair<String, Float> {
        val progs = guideEpg[ch.id].orEmpty()
        if (progs.isEmpty()) return "Loading guide…" to 0f
        val now = System.currentTimeMillis()
        val cur = progs.firstOrNull { it.contains(now) }
        val next = progs.firstOrNull { it.start >= (cur?.end ?: now) }
        val text = when {
            cur != null -> "${timeFmt.format(java.util.Date(cur.start))} – " +
                "${timeFmt.format(java.util.Date(cur.end))} · ${cur.title}"
            next != null -> "Next ${timeFmt.format(java.util.Date(next.start))} · ${next.title}"
            else -> "No guide data"
        }
        val frac = if (cur != null) {
            ((now - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1L)).coerceIn(0f, 1f)
        } else 0f
        return text to frac
    }

    /**
     * Fetch now/next for the channels in a list page. The guide page loads its own rows;
     * list rows used to show nothing because nothing ever asked for their guide data.
     * Bounded and cooled down so scrolling a 1,000-channel list does not hammer the panel.
     */
    private fun ensureRowGuide(channels: List<Channel>) {
        if (guideDisabled || channels.isEmpty()) return
        val now = System.currentTimeMillis()
        val todo = channels.asSequence()
            .filter { it.id !in guideEpg }
            .filter { (guideRetryAt[it.id] ?: 0L) <= now }
            .take(14)
            .toList()
        if (todo.isEmpty()) return
        todo.forEach { guideRetryAt[it.id] = now + 60_000L }
        scope.launch {
            todo.forEach { ch ->
                val epg = withContext(Dispatchers.IO) {
                    runCatching { repo.epgFor(ch) }.getOrNull()
                }.orEmpty()
                if (epg.isNotEmpty()) {
                    guideEpg[ch.id] = epg
                    withContext(Dispatchers.Main) { rowAdapter?.notifyDataSetChanged() }
                }
            }
        }
    }

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
