package com.mytv.app.car

import android.content.Intent
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mytv.app.MyApplication
import com.mytv.app.data.Channel
import com.mytv.app.player.PlaybackService

/**
 * The car session: a channel list in the navigation category (nav category =
 * unrestricted surface) plus full-screen video on the surface the head unit
 * provides.
 *
 * The one thing taken from Fermata's design is the rule that the *car* owns the
 * geometry. We honour it through the supported Car App Library surface
 * ([CarVideoSurface]) instead of the deprecated proprietary car SDK, and once a
 * channel is picked we push a bare [NavigationTemplate] so the surface — not a
 * list — covers the screen. Hosts that never hand us a surface fall back to the
 * projected [ProjectionActivity].
 */
class ProjectionSession : Session() {

    private var videoSurface: CarVideoSurface? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val surface = CarVideoSurface(carContext).also { videoSurface = it }
        val appManager = runCatching { carContext.getCarService(AppManager::class.java) }.getOrNull()
        if (appManager == null) {
            Log.w("ProjectionSession", "no AppManager: falling back to activity projection")
        } else {
            runCatching { appManager.setSurfaceCallback(surface) }
                .onFailure { Log.w("ProjectionSession", "surface callback rejected", it) }
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    runCatching { appManager.setSurfaceCallback(null) }
                }
            })
        }
        return ChannelListScreen(carContext, surface)
    }

    private class ChannelListScreen(
        private val ctx: CarContext,
        private val video: CarVideoSurface,
    ) : Screen(ctx) {

        override fun onGetTemplate(): Template {
            val app = ctx.applicationContext as MyApplication
            val pl = app.repository.activePlaylist
            val listBuilder = ItemList.Builder()

            if (pl == null || pl.channels.isEmpty()) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("No playlist loaded")
                        .addText("Add an M3U or Xtream source from the phone app.")
                        .build()
                )
            } else {
                pl.channels.forEach { ch ->
                    val onClick = ParkedOnlyOnClickListener.create {
                        startChannel(ch)
                    }
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(ch.name)
                            .addText(ch.group)
                            .setOnClickListener(onClick)
                            .build()
                    )
                }
            }

            return PlaceListNavigationTemplate.Builder()
                .setTitle("MYTV — Live")
                .setHeaderAction(Action.APP_ICON)
                .setItemList(listBuilder.build())
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(
                            Action.Builder()
                                .setTitle("MYTV")
                                .build()
                        )
                        .build()
                )
                .build()
        }

        private fun startChannel(ch: Channel) {
            if (ch.url.isBlank()) return
            val play = Intent(ctx.applicationContext, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_URL, ch.url)
                .putExtra(PlaybackService.EXTRA_TITLE, ch.name)
                .putExtra(PlaybackService.EXTRA_POSITION, 0L)
            ctx.applicationContext.startService(play)

            video.title = ch.name
            if (video.lastWidth > 0) {
                // Surface is live: hand the whole screen to the video.
                screenManager.push(VideoScreen(ctx, ch.name))
                return
            }

            // Host gave us no surface — project the activity instead.
            val projection = Intent(ctx, ProjectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("title", ch.name)
            ctx.startActivity(projection)
        }
    }

    /**
     * Bare navigation template: no list, no navigation info, just a Stop action.
     * The car's surface fills everything behind it, so the video gets the screen.
     */
    private class VideoScreen(
        private val ctx: CarContext,
        private val channel: String,
    ) : Screen(ctx) {

        override fun onGetTemplate(): Template = NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    // Head units have no system back: this pops back to the channel
                    // list and leaves the stream running, like tapping a row again.
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
                                    runCatching {
                                        ctx.applicationContext.startService(
                                            Intent(ctx.applicationContext, PlaybackService::class.java)
                                                .setAction(PlaybackService.ACTION_PAUSE)
                                        )
                                    }
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
}
