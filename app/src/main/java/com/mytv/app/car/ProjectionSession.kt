package com.mytv.app.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import com.mytv.app.MyApplication
import com.mytv.app.data.Channel

/**
 * The car Session. Presents the active playlist's channels as a scrollable
 * list in the navigation category (nav category = unrestricted surface).
 * Selecting a channel starts playback on the shared ExoPlayer and projects it
 * fullscreen onto the car via [ProjectionActivity].
 */
class ProjectionSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen = ChannelListScreen(carContext)

    private class ChannelListScreen(private val ctx: CarContext) : Screen(ctx) {

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
            // Start playback via the phone service, then project fullscreen.
            val play = Intent(ctx.applicationContext, com.mytv.app.player.PlaybackService::class.java)
                .setAction(com.mytv.app.player.PlaybackService.ACTION_PLAY)
                .putExtra(com.mytv.app.player.PlaybackService.EXTRA_URL, ch.url)
                .putExtra(com.mytv.app.player.PlaybackService.EXTRA_TITLE, ch.name)
                .putExtra(com.mytv.app.player.PlaybackService.EXTRA_POSITION, 0L)
            ctx.applicationContext.startService(play)

            val projection = Intent(ctx, ProjectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("title", ch.name)
            ctx.startActivity(projection)
        }
    }
}
