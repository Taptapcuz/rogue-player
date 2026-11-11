package com.mytv.app.player

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the ExoPlayer instance. Kept as a service so audio/stream continues
 * when the phone UI is not in front (needed for background/car playback),
 * and so the car projection surface can attach to the same player.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()

    private var player: ExoPlayer? = null

    /** A live channel that also shows on the car projection surface. */
    var currentTitle: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "MYTV"
                val pos = intent.getLongExtra(EXTRA_POSITION, 0L)
                play(url, title, pos)
            }
            ACTION_PAUSE -> pause()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        release()
        scope.cancel()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun getPlayer(): ExoPlayer {
        val existing = player ?: run {
            val newPlayer = ExoPlayer.Builder(this)
                .build()
            player = newPlayer
            newPlayer
        }
        return existing
    }

    fun play(url: String, title: String) {
        currentTitle = title
        val p = getPlayer()
        p.setMediaItem(buildMediaItem(url))
        p.prepare()
        p.playWhenReady = true
    }

    fun play(url: String, title: String, positionMs: Long) {
        currentTitle = title
        val p = getPlayer()
        p.setMediaItem(buildMediaItem(url))
        p.prepare()
        p.seekTo(positionMs)
        p.playWhenReady = true
    }

    fun playNow() {
        player?.playWhenReady = true
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun stop() {
        player?.let { exo ->
            exo.stop()
            exo.clearMediaItems()
        }
    }

    fun release() {
        player?.release()
        player = null
    }

    fun currentPosition(): Long = player?.currentPosition ?: 0L

    fun isPlaying(): Boolean = player?.isPlaying ?: false

    private fun buildMediaItem(url: String): MediaItem {
        // HLS support: media3 detects HLS from content-type, but forcing
        // HlsMediaSource for .m3u8 URLs is more reliable.
        val uri = Uri.parse(url)
        return MediaItem.fromUri(uri)
    }

    companion object {
        const val ACTION_PLAY = "com.mytv.app.PLAY"
        const val ACTION_PAUSE = "com.mytv.app.PAUSE"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"

        fun toPlaybackActions(intent: Intent): String? = intent.action
    }
}
