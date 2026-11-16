package com.mytv.app.car

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
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.mytv.app.MyApplication
import com.mytv.app.data.Channel
import com.mytv.app.player.PlaybackService

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
                val favIds = favourites(ctx)
                val favs = if (favIds.isEmpty()) emptyList()
                else pl.channels.filter { it.id in favIds }
                if (favs.isNotEmpty()) {
                    list.addItem(
                        Row.Builder()
                            .setTitle("★ Favourites")
                            .addText("${favs.size} channels")
                            .setOnClickListener(
                                ParkedOnlyOnClickListener.create {
                                    screenManager.push(
                                        ChannelListScreen(ctx, video, play, "Favourites", favs)
                                    )
                                }
                            )
                            .build()
                    )
                }

                val groups = pl.channels.groupBy { it.group.ifBlank { "Ungrouped" } }
                groups.keys.sorted().take(MAX_GROUPS).forEach { group ->
                    val channels = groups.getValue(group)
                    list.addItem(
                        Row.Builder()
                            .setTitle(group)
                            .addText("${channels.size} channels")
                            .setOnClickListener(
                                ParkedOnlyOnClickListener.create {
                                    screenManager.push(
                                        ChannelListScreen(ctx, video, play, group, channels)
                                    )
                                }
                            )
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
                        .addAction(Action.Builder().setTitle("MYTV").build())
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
                        .setOnClickListener(
                            ParkedOnlyOnClickListener.create { startChannel(ch) }
                        )
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
            // what killed the video ~180ms after it started. RoutingInfo(loading) is
            // the same state Google's sample uses mid-reroute: it declares that a
            // navigation session is in progress without claiming a route we don't have.
            .setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            .setActionStrip(
                ActionStrip.Builder()
                    // Head units have no system back: this pops back to the channel
                    // list and leaves the stream running.
                    .addAction(
                        Action.Builder()
                            .setTitle("Channels")
                            .setOnClickListener { screenManager.pop() }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Stop")
                            .setOnClickListener(
                                ParkedOnlyOnClickListener.create {
                                    play.pause()
                                    screenManager.pop()
                                }
                            )
                            .build()
                    )
                    .build()
            )
            .build()

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
