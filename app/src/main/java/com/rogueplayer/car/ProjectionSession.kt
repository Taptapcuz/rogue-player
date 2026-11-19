package com.rogueplayer.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.rogueplayer.MyApplication
import com.rogueplayer.data.Channel
import com.rogueplayer.player.PlaybackService

/**
 * The car session: browse templates in the navigation category (nav category =
 * unrestricted surface) plus full-screen video on the surface the head unit
 * provides.
 *
 * Two hard car rules are encoded here:
 *
 * 1. **Never put the whole playlist in one template.** This screen used to add one
 *    Row per channel — 1,041 on this account — and the host answers that with a
 *    failed/abandoned template (Binder payload size plus the render deadline),
 *    which is exactly what "shows up, doesn't open fully, then error" looks like.
 *    The root list is now Favourites + provider groups, and a group screen caps at
 *    [MAX_ROWS].
 *
 * 2. **Never startService from a car screen.** A projected session is not a
 *    foreground app, so `startService` throws
 *    BackgroundServiceStartNotAllowedException (the same failure that used to
 *    crash the guide preview). Playback now goes through a *bound*
 *    [PlaybackService], with the request queued until the binding lands.
 */
class ProjectionSession : Session() {

    private var videoSurface: CarVideoSurface? = null
    private val carPlay by lazy { CarPlay(carContext) }

    override fun onCreateScreen(intent: Intent): Screen {
        val surface = CarVideoSurface(carContext) { s -> carPlay.renderTo(s) }
            .also { videoSurface = it }
        // The surface callback is registered by VideoScreen, not here: AA allocates
        // a navigation surface per *screen*, and the sample (android/car-samples
        // navigation SurfaceRenderer) registers it from the screen's lifecycle. Doing
        // it at session level meant the host handed us a surface while no navigation
        // template was up, and reclaimed it ~180ms later.
        return RootScreen(carContext, surface, carPlay)
    }

    /**
     * Bound-service playback. A car session can run with no Activity at all, so
     * binding is the only legal way to reach the player.
     */
    private class CarPlay(private val ctx: CarContext) {
        private var svc: PlaybackService? = null
        private var binding = false
        private var pending: Pair<String, String>? = null

        /** Surface to attach once the binding lands. */
        private var pendingSurface: android.view.Surface? = null

        private val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                svc = (binder as? PlaybackService.LocalBinder)?.getService()
                binding = false
                // Apply a request that arrived before the binding completed.
                pending?.let { (url, title) ->
                    pending = null
                    svc?.play(url, title, 0L)
                }
                pendingSurface?.let { surface ->
                    svc?.setVideoSurface(surface)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                svc = null
            }
        }

        fun play(url: String, title: String) {
            if (url.isBlank()) return
            val ready = svc
            if (ready != null) {
                ready.play(url, title, 0L)
                return
            }
            pending = url to title
            if (binding) return
            binding = runCatching {
                ctx.bindService(
                    Intent(ctx, PlaybackService::class.java),
                    conn,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrElse {
                Log.w(TAG, "bind to playback failed", it)
                false
            }
        }

        /** Attach/detach the car's surface as the host hands it over. */
        fun renderTo(surface: android.view.Surface?) {
            val ready = svc
            if (ready != null) {
                ready.setVideoSurface(surface)
                return
            }
            pendingSurface = surface
            if (binding) return
            binding = runCatching {
                ctx.bindService(
                    Intent(ctx, PlaybackService::class.java),
                    conn,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrElse {
                Log.w(TAG, "bind for surface failed", it)
                false
            }
        }

        fun pause() {
            runCatching { svc?.pause() }
        }

        /** Title of whatever is loaded right now, so a list screen can jump back to it. */
        fun currentTitle(): String? = svc?.currentTitle?.takeIf { it.isNotBlank() }

        fun unbind() {
            runCatching { ctx.unbindService(conn) }
            binding = false
            svc = null
        }
    }

    // ------------------------------------------------------------ screens ---

    /** Favourites first, then one row per provider group — never the flat list. */
    private class RootScreen(
        private val ctx: CarContext,
        private val video: CarVideoSurface,
        private val play: CarPlay,
    ) : Screen(ctx) {

        private val repo = (ctx.applicationContext as MyApplication).repository

        init {
            // A cold start can reach this screen before the stored playlists are
            // parsed (that work is off-thread now, see PlaylistRepository.init).
            // Ask for a re-render once they land instead of claiming "no playlist".
            val job = CoroutineScope(Dispatchers.Main).launch {
                repo.loadState.first { it }
                invalidate()
            }
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    job.cancel()
                }
            })
        }

        override fun onGetTemplate(): Template {
            val pl = repo.activePlaylist
            val list = ItemList.Builder()

            if (pl == null || pl.channels.isEmpty()) {
                val loading = !repo.loadState.value
                list.addItem(
                    Row.Builder()
                        .setTitle(if (loading) "Loading channels…" else "No playlist loaded")
                        .addText(
                            if (loading) "Reading your sources from storage."
                            else "Add an M3U or Xtream source from the phone app."
                        )
                        .build()
                )
            } else {
                // Categories hidden in the phone's playlist manager stay out of the car too.
                val hidden = repo.hiddenGroups(pl.name)
                val shown = if (hidden.isEmpty()) pl.channels
                            else pl.channels.filterNot { it.group in hidden }
                val favIds = favourites(ctx)
                val favs = if (favIds.isEmpty()) emptyList()
                else shown.filter { it.id in favIds }
                if (favs.isNotEmpty()) {
                    list.addItem(
                        Row.Builder()
                            .setTitle("★ Favourites")
                            .addText("${favs.size} channels")
                            .setOnClickListener {
                                screenManager.push(
                                    ChannelListScreen(ctx, video, play, "Favourites", favs)
                                )
                            }
                            .build()
                    )
                }

                val groups = shown.groupBy { it.group.ifBlank { "Ungrouped" } }
                groups.keys.sorted().take(MAX_GROUPS).forEach { group ->
                    val channels = groups.getValue(group)
                    list.addItem(
                        Row.Builder()
                            .setTitle(group)
                            .addText("${channels.size} channels")
                            .setOnClickListener {
                                screenManager.push(
                                    ChannelListScreen(ctx, video, play, group, channels)
                                )
                            }
                            .build()
                    )
                }
                if (groups.size > MAX_GROUPS) {
                    list.addItem(
                        Row.Builder()
                            .setTitle("${groups.size - MAX_GROUPS} more groups")
                            .addText("Browse the rest from the phone app.")
                            .build()
                    )
                }
            }

            return ListTemplate.Builder()
                .setTitle("MYTV — Live")
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(list.build())
                .setActionStrip(
                    ActionStrip.Builder()
                        // A list template allows ONE custom-titled action, so YouTube is an
                        // icon here — "MYTV" + "YouTube" + "▶ Stream" as titles threw
                        // "Action list exceeded max number of 1 actions with custom titles"
                        // and crashed the car app on open.
                        .addAction(
                            Action.Builder()
                                .setIcon(youtubeIcon())
                                .setOnClickListener {
                                    screenManager.push(
                                        CarWebScreen(ctx, "https://m.youtube.com")
                                    )
                                }
                                .build()
                        )
                        .apply {
                            play.currentTitle()?.let { title ->
                                addAction(
                                    Action.Builder()
                                        .setTitle("▶ Stream")
                                        .setOnClickListener {
                                            screenManager.push(VideoScreen(ctx, video, play, title))
                                        }
                                        .build()
                                )
                            }
                        }
                        .build()
                )
                .build()
        }
    }

    /** One group's channels, capped so the template always stays small. */
    private class ChannelListScreen(
        private val ctx: CarContext,
        private val video: CarVideoSurface,
        private val play: CarPlay,
        private val group: String,
        private val channels: List<Channel>,
    ) : Screen(ctx) {

        override fun onGetTemplate(): Template {
            val list = ItemList.Builder()
            channels.take(MAX_ROWS).forEach { ch ->
                list.addItem(
                    Row.Builder()
                        .setTitle(ch.name)
                        .apply { if (ch.group.isNotBlank()) addText(ch.group) }
                        .setOnClickListener { startChannel(ch) }
                        .build()
                )
            }
            if (channels.size > MAX_ROWS) {
                list.addItem(
                    Row.Builder()
                        .setTitle("Showing $MAX_ROWS of ${channels.size}")
                        .addText("Narrow it down from the phone app's search.")
                        .build()
                )
            }

            return ListTemplate.Builder()
                .setTitle("MYTV — $group")
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                // A head unit has no system back button, and popping the video screen
                // leaves the stream running with no way to see it again. This is that
                // way back: push the video screen again for whatever is loaded.
                .apply {
                    play.currentTitle()?.let { title ->
                        setActionStrip(
                            ActionStrip.Builder()
                                .addAction(
                                    Action.Builder()
                                        .setTitle("▶ Stream")
                                        .setOnClickListener {
                                            screenManager.push(VideoScreen(ctx, video, play, title))
                                        }
                                        .build()
                                )
                                .build()
                        )
                    }
                }
                .build()
        }

        private fun startChannel(ch: Channel) {
            if (ch.url.isBlank()) return
            play.play(ch.url, ch.name)
            // Push the navigation template FIRST. The host only hands a navigation
            // app its drawable surface once a NavigationTemplate is on screen, so
            // waiting for `video.lastWidth > 0` before showing it deadlocked: no
            // template → no surface → no video. CarVideoSurface draws as soon as
            // the surface arrives.
            //
            // The old fallback (launching ProjectionActivity from here) cannot work:
            // a car session is a background app, and starting an activity from it is
            // rejected (`SafeActivityOptions.checkPermissions` RemoteException).
            screenManager.push(VideoScreen(ctx, video, play, ch.name))
        }
    }

    /**
     * Bare navigation template: no list, no navigation info, just Channels/Stop.
     * The car's surface fills everything behind it, so the video gets the screen.
     */
    private class VideoScreen(
        private val ctx: CarContext,
        private val video: CarVideoSurface,
        private val play: CarPlay,
        private val channel: String,
    ) : Screen(ctx) {

        init {
            // Register the surface callback for exactly this screen's lifetime, the
            // way the Car App Library navigation sample does. AA hands a navigation
            // app its drawable surface only for the screen that shows a navigation
            // template with navigation info attached.
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    runCatching { ctx.getCarService(AppManager::class.java).setSurfaceCallback(video) }
                        .onFailure { Log.w(TAG, "surface callback rejected", it) }
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    runCatching { ctx.getCarService(AppManager::class.java).setSurfaceCallback(null) }
                }
            })
        }

        override fun onGetTemplate(): Template = NavigationTemplate.Builder()
            // Without navigation info the host treats the template as "no active
            // navigation", collapses the map area and destroys the surface — which is
            // what killed the video ~180ms after it started. Two warnings from getting
            // this wrong: `RoutingInfo(loading=true)` keeps the surface but paints a
            // permanent spinner in the top-left over the video, and
            // `RoutingInfo(loading=false)` throws
            // "Current step and distance must be set during the navigating state".
            // A `Trip` is not a `NavigationTemplate.NavigationInfo` in car-app 1.7 (only
            // `RoutingInfo` implements it), and `RoutingInfo(loading=false)` refuses to
            // build without a current step ("Current step and distance must be set
            // during the navigating state"), so a real step is supplied below.
            .setNavigationInfo(liveRouting())
            // `NavigationTemplate` refuses to build without a header action strip
            // ("Action strip for this template must be set"). It also holds the only
            // controls that respond to a *single* tap: the map action strip sits over the
            // nav surface, so the host swallows the first tap there ("you have to tap
            // twice"). Both sets of controls are therefore provided.
            .setActionStrip(headerStrip())
            // The picture controls: small round icons at the bottom, over the video.
            .setMapActionStrip(
                ActionStrip.Builder()
                    // Head units have no system back: this pops back to the channel
                    // list and leaves the stream running.
                    .addAction(
                        Action.Builder()
                            .setIcon(backIcon())
                            .setOnClickListener { screenManager.pop() }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setIcon(stopIcon())
                            // Active by choice: like the browse rows, this action must
                            // work while the head unit reports driving, so the stream can
                            // be controlled from the seat without a parking brake.
                            .setOnClickListener {
                                play.pause()
                                screenManager.pop()
                            }
                            .build()
                    )
                    .build()
            )
            .build()

        /**
         * `Action.APP_ICON` only. Measured on the DHU: the host keeps drawing the **map**
         * action strip (the bottom back/stop glyphs) only while the header strip holds this
         * single predefined action. One custom icon there (transparent or not, one or two)
         * makes the bottom strip disappear entirely, and titled actions don't render at all.
         * The price is the small circle in the top-right corner, which this template cannot
         * remove. The bottom strip is the tap target that matters; it needs two taps because
         * the first one lands on the nav surface the host draws underneath it.
         */
        private fun headerStrip(): ActionStrip =
            ActionStrip.Builder().addAction(Action.APP_ICON).build()

        /** Chevron-left, for "back to the channel list". */
        private fun backIcon(): CarIcon = glyphIcon { c, paint ->
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            c.drawLine(w * 0.58f, h * 0.20f, w * 0.34f, h * 0.50f, paint)
            c.drawLine(w * 0.34f, h * 0.50f, w * 0.58f, h * 0.80f, paint)
        }

        /** Filled square, for "stop". */
        private fun stopIcon(): CarIcon = glyphIcon { c, paint ->
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            val bar = minOf(w, h) * 0.30f
            val cx = w / 2f
            val cy = h / 2f
            val fill = android.graphics.Paint(paint)
            fill.style = android.graphics.Paint.Style.FILL
            c.drawRoundRect(cx - bar, cy - bar, cx + bar, cy + bar, 4f, 4f, fill)
        }

        /** Draw a white glyph on a transparent 64x64 bitmap for a car action. */
        private fun glyphIcon(draw: (android.graphics.Canvas, android.graphics.Paint) -> Unit): CarIcon {
            val size = 64
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 8f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            draw(canvas, paint)
            return CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)).build()
        }

        /**
         * Non-loading routing state with nothing visible on top of the video.
         *
         * The host only keeps the surface (and so the video) while it believes a
         * navigation session is running. Three shapes were tried on the DHU:
         * - no navigation info → host collapses the map area and destroys the surface;
         * - `loading=true` → permanent spinner in the top-left over the picture;
         * - `loading=false` + a real step + destination estimate → a grey card with the
         *   channel name, "1 min", arrival time and "0 m".
         * The model refuses to build the non-loading shape without a step and distance,
         * so the step text is a non-breaking space: it satisfies the validator and the
         * host draws an empty strip instead of a card.
         */
        private fun liveRouting(): RoutingInfo =
            RoutingInfo.Builder()
                .setLoading(false)
                .setCurrentStep(
                    Step.Builder(BLANK).setRoad(BLANK).build(),
                    Distance.create(0.0, Distance.UNIT_METERS),
                )
                .build()

        /** Non-empty after `trim()`, renders as nothing. */
        private val BLANK = "\u00A0"

        override fun toString(): String = "VideoScreen($channel)"
    }

    private companion object {
        const val TAG = "MYTVCar"

        /** Group rows on the root screen, and channel rows inside one group. */
        const val MAX_GROUPS = 30
        const val MAX_ROWS = 60

        /** Same prefs file the phone UI writes, so stars are shared. */
        fun favourites(ctx: CarContext): Set<String> =
            ctx.getSharedPreferences("mytv_favorites", Context.MODE_PRIVATE)
                .getStringSet("ids", emptySet())
                ?.toSet()
                .orEmpty()
    }
}

/**
 * Play-button glyph for the "YouTube" action on the root list.
 *
 * It has to be an icon rather than a title: a `ListTemplate` action strip accepts exactly
 * one custom-titled action, and the strip already carries "▶ Stream" when something is
 * playing. Car icons are tinted by the host, so the glyph is drawn white on transparent.
 */
private fun youtubeIcon(): CarIcon {
    val size = 64
    val bmp = android.graphics.Bitmap.createBitmap(
        size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    val path = android.graphics.Path().apply {
        moveTo(size * 0.36f, size * 0.24f)
        lineTo(size * 0.36f, size * 0.76f)
        lineTo(size * 0.78f, size * 0.50f)
        close()
    }
    c.drawPath(path, paint)
    return CarIcon.Builder(
        androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)).build()
}
