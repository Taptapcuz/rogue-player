package com.rogueplayer.car

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rogueplayer.R
import com.rogueplayer.ui.Palette
import com.rogueplayer.ui.SplashView
import com.rogueplayer.ui.UiSettings

/** The car quick-access targets — a subset of the phone's Home grid (Guide & Playlists stay phone-only). */
enum class CarLandingTile(val label: String, val iconRes: Int) {
    LIVE("Live TV", R.drawable.ic_nav_live),
    MOVIES("Movies", R.drawable.ic_nav_movies),
    SHOWS("TV Shows", R.drawable.ic_nav_tv),
    YOUTUBE("YouTube", R.drawable.ic_nav_youtube),
}

/**
 * The car's home screen, rendered (not templated) so it looks like the phone app:
 *
 * 1. On a fresh session the neon [SplashView] boot plays into the car surface.
 * 2. When it ends (and on any later re-attach), the grid landing page takes over —
 *    the same city backdrop + tagline + 3×2 icon-tile grid as the phone's /Home.
 * 3. Taps delivered by the surface are replayed into the page so each tile is a
 *    real button; which screen a tile opens is owned by [onTile] (wired by the
 *    session, which knows the real car screens).
 *
 * Mechanics are CarWebScreen's: the host's navigation `Surface` becomes a
 * `VirtualDisplay`, a `Presentation` is shown on it, and plain Views live inside.
 * The splash runs at most once per session — [splashDone] survives surface
 * reclaim/re-attach when a tile's list template covers this screen and back
 * returns, so coming back never replays the boot.
 */
class CarLandingScreen(
    private val ctx: CarContext,
    private val onTile: (screen: CarLandingScreen, tile: CarLandingTile) -> Unit,
    private val onWeb: (screen: CarLandingScreen) -> Unit,
) : Screen(ctx) {

    private val handler = Handler(Looper.getMainLooper())

    private var display: VirtualDisplay? = null
    private var presentation: android.app.Presentation? = null
    private var root: FrameLayout? = null
    private var splashDone = false
    private var viewW = 0
    private var viewH = 0

    /** Last surface + geometry, so a resume can re-show the page without a fresh
     *  onSurfaceAvailable (AA often keeps the surface across background/foreground). */
    private var lastSurface: Surface? = null
    private var lastW = 0
    private var lastH = 0
    private var lastDpi = 0

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val surface = container.surface ?: return
            val w = container.width
            val h = container.height
            val dpi = container.dpi
            lastSurface = surface; lastW = w; lastH = h; lastDpi = dpi
            Log.i(TAG, "landing surface ${w}x$h @${dpi}dpi")
            // Post (and delay) — building inside onSurfaceAvailable is what the host
            // reports as ANR API: ON_SURFACE_AVAILABLE (see CarWebScreen).
            handler.postDelayed({ attach(surface, w, h, dpi) }, 250L)
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            Log.i(TAG, "landing surface destroyed")
            lastSurface = null
            handler.post { detach() }
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {}
        override fun onClick(x: Float, y: Float) { tap(x, y) }
        override fun onScroll(dx: Float, dy: Float) {}
        override fun onFling(dx: Float, dy: Float) {}
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                runCatching {
                    ctx.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
                }.onFailure { Log.w(TAG, "surface callback rejected", it) }
            }

            /**
             * Re-entering the car app: AA often keeps the navigation surface but does
             * NOT re-issue onSurfaceAvailable, and the Presentation was dismissed when
             * we backgrounded — which left a BLACK screen ("stuck"). Re-show from the
             * retained surface if it's still valid. Do NOT invalidate() or re-register
             * the callback here — that made the host churn the surface ~1/s.
             */
            override fun onStart(owner: LifecycleOwner) {
                val s = lastSurface
                if (root == null && s != null && s.isValid()) {
                    attach(s, lastW, lastH, lastDpi)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                runCatching {
                    ctx.getCarService(AppManager::class.java).setSurfaceCallback(null)
                }
                detach()
            }
        })
    }

    private fun attach(surface: Surface, width: Int, height: Int, dpi: Int) {
        detach()
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "ignoring empty surface ${width}x$height")
            return
        }
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return
        display = dm.createVirtualDisplay(
            "mytv-landing",
            width,
            height,
            if (dpi > 0) dpi else 160,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val vd = display ?: return

        val presentation = android.app.Presentation(ctx, vd.display)
        val frame = FrameLayout(presentation.context).apply {
            setBackgroundColor(Palette.BG)
        }
        presentation.setContentView(frame)
        runCatching { presentation.show() }
            .onFailure { Log.w(TAG, "presentation refused to show", it) }

        this.presentation = presentation
        this.root = frame
        viewW = width
        viewH = height

        if (!splashDone) showSplash() else showLanding()
    }

    /** Phase 1: the neon boot, full-screen. [splashDone] flips so a re-attach skips it. */
    private fun showSplash() {
        val frame = root ?: return
        frame.removeAllViews()
        // Full-bleed on the car surface: flames/orbs cover the whole screen, and
        // SplashView now sizes the wordmark to fit BOTH width and height (no clipping).
        val sv = SplashView(frame.context)
        frame.addView(sv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sv.start { handler.post { splashDone = true; showLanding() } }
    }

    /** Phase 2: the landing page — city + scrim + tagline + 3×2 grid + web pill. */
    private fun showLanding() {
        val frame = root ?: return
        frame.removeAllViews()
        frame.addView(buildLanding(frame.context), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun detach() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        display?.let { runCatching { it.release() } }
        display = null
        root = null
    }

    /** Replay a car tap as a down/up into the page so tiles are real buttons. */
    private fun tap(x: Float, y: Float) { handler.post {
        val frame = root ?: return@post
        val px = if (viewW > 0) x * frame.width / viewW else x
        val py = if (viewH > 0) y * frame.height / viewH else y
        val down = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, px, py, 0)
        frame.dispatchTouchEvent(d); d.recycle()
        val up = SystemClock.uptimeMillis()
        val u = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, px, py, 0)
        frame.dispatchTouchEvent(u); u.recycle()
    } }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------- template ---
    // A NavigationTemplate (with live routing) is what keeps AA handing us the
    // surface; without it the map area collapses and the surface dies ~180ms in.
    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setNavigationInfo(routing())
        .setActionStrip(
            ActionStrip.Builder()
                // Exit the car app. On the DHU the host relaunches us (boot reload);
                // on a real head unit finishCarApp() returns to the AA home. Worth
                // testing in-car.
                .addAction(
                    Action.Builder()
                        .setIcon(backIcon())
                        .setOnClickListener { runCatching { ctx.finishCarApp() } }
                        .build()
                )
                .build())
        .build()

    /** Drawn chevron for the header action (map strip takes no titles; header icons). */
    private fun backIcon(): androidx.car.app.model.CarIcon {
        val size = 64
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = size * 0.11f
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val w = size.toFloat(); val h = size.toFloat()
        c.drawLine(w * 0.60f, h * 0.20f, w * 0.34f, h * 0.50f, p)
        c.drawLine(w * 0.34f, h * 0.50f, w * 0.60f, h * 0.80f, p)
        return androidx.car.app.model.CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)).build()
    }

    private fun routing(): RoutingInfo = RoutingInfo.Builder()
        .setLoading(false)
        .setCurrentStep(
            Step.Builder(BLANK).setRoad(BLANK).build(),
            Distance.create(0.0, Distance.UNIT_METERS),
        )
        .build()

    private val BLANK = "\u00A0"

    // ----------------------------------------------------- landing view ---
    private fun buildLanding(c: Context): View {
        val host = FrameLayout(c).apply { setBackgroundColor(Palette.BG) }

        // City backdrop + readability scrim (same as the phone landing).
        host.addView(ImageView(c).apply {
            setImageResource(R.drawable.landing_city)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(View(c).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(80, 0, 0, 0), Color.argb(120, 0, 0, 0)))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Vertically-centred column carrying the tagline, the grid, the pill.
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        host.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        col.addView(text(c, "STREAM EVERYTHING · ONE PLAYER", 11f, Palette.MUTED, true))
        col.addView(
            text(c, "LIVE · MOVIES · TV SHOWS · YOUTUBE", 10f, Palette.DIM, false),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) })

        val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0) }
        CarLandingTile.entries.chunked(2).forEachIndexed { rowIdx, row ->
            val rowLay = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.forEach { t ->
                rowLay.addView(
                    tile(c, t, rowIdx),
                    LinearLayout.LayoutParams(0, dp(116), 1f).apply {
                        marginStart = dp(6); marginEnd = dp(6)
                    },
                )
            }
            grid.addView(rowLay)
        }
        col.addView(grid)

        // Transparent web-search pill (Google in the car, no keyboard needed on load).
        col.addView(webPill(c), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(74) })

        return host
    }

    private fun text(c: Context, s: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(c).apply {
            text = s
            textSize = size
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
        }

    /** One landing tile: clean accent icon over a bold label — no card box. */
    private fun tile(c: Context, t: CarLandingTile, row: Int): View =
        object : LinearLayout(c) {
            // Give each tile a drawable home-bounds background so the surface tap
            // hits its own click target instead of leaking to the page behind.
            init {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { onTile(this@CarLandingScreen, t) }
            }
        }.apply {
            addView(ImageView(c).apply {
                setImageResource(t.iconRes)
                // Alternate row accent: theme accent, violet on the middle row — same as the phone.
                setColorFilter(if (row == 1) UiSettings.accent(c, 2) else UiSettings.accent(c, 0))
                contentDescription = t.label
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(c).apply {
                text = t.label
                textSize = 12.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Palette.TEXT)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
        }

    /** Transparent web-search pill — accent magnifier + "Search the web" hint. */
    private fun webPill(c: Context): View =
        object : LinearLayout(c) {
            init {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = null
                setPadding(dp(18), dp(14), dp(18), dp(14))
                setOnClickListener { onWeb(this@CarLandingScreen) }
            }
        }.apply {
            addView(text(c, "\u2315", 24f, Palette.ACCENTS[0], false).also {
                it.gravity = Gravity.CENTER
            })
            addView(text(c, "Search the web", 14f, Palette.TEXT, true).also {
                it.gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14); marginEnd = dp(6)
            })
            addView(text(c, "\uD83D\uDD0D", 15f, Palette.DIM, false).also {
                it.gravity = Gravity.CENTER
            })
        }

    private companion object {
        const val TAG = "CarLanding"
    }
}
