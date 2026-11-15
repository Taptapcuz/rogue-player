package com.mytv.app.player

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the ExoPlayer instance. Kept as a service so audio/stream continues
 * when the phone UI is not in front (needed for background/car playback),
 * and so the car projection surface can attach to the same player.
 *
 * Exposes a stable, observable [PlaybackState] for any UI (phone screen or
 * car projection) to render, plus an ExoPlayer the projection surface uses.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /** The channel / entry currently loaded. */
    var currentTitle: String = ""
        private set

    /** Latest observable state for UI rendering. */
    var state: PlaybackState = PlaybackState.Idle
        private set

    /** Extra UI observers (the phone player screen) attached to the player. */
    private val observers = mutableListOf<Player.Listener>()

    /** Subscribe to raw ExoPlayer events. */
    fun attach(listener: Player.Listener) {
        observers.add(listener)
        player?.addListener(listener)
    }

    fun detach(listener: Player.Listener) {
        observers.remove(listener)
        player?.removeListener(listener)
    }

    // Singleton listener that turns player events into the stable [state].
    private val stateListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refreshState()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshState()
        override fun onPlayerError(error: PlaybackException) {
            state = PlaybackState.Error(error.message ?: "Playback failed")
        }
    }

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
                .setHandleAudioBecomingNoisy(true)
                .build()
            newPlayer.addListener(stateListener)
            observers.forEach { newPlayer.addListener(it) }
            player = newPlayer
            // MediaSession powers lock-screen / media notification controls.
            mediaSession = MediaSession.Builder(this, newPlayer).build()
            newPlayer
        }
        return existing
    }

    fun play(url: String, title: String) = play(url, title, 0L)

    fun play(url: String, title: String, positionMs: Long) {
        currentTitle = title
        state = PlaybackState.Buffering
        val p = getPlayer()
        p.setMediaSource(buildMediaSource(url))
        p.prepare()
        if (positionMs > 0) p.seekTo(positionMs)
        p.playWhenReady = true
    }

    fun playNow() {
        player?.playWhenReady = true
        refreshState()
    }

    fun pause() {
        player?.playWhenReady = false
        refreshState()
    }

    fun stop() {
        Log.d("MYTVPlay", "stop()")
        player?.let { exo ->
            exo.stop()
            exo.clearMediaItems()
        }
        state = PlaybackState.Idle
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        state = PlaybackState.Idle
    }

    fun currentPosition(): Long = player?.currentPosition ?: 0L

    fun isPlaying(): Boolean = player?.isPlaying ?: false

    /** Derive [state] from the live player snapshot. */
    private fun refreshState() {
        val p = player ?: return
        if (state is PlaybackState.Error) return // keep last error until next play
        val pState = p.playbackState
        state = when {
            p.isPlaying -> PlaybackState.Playing
            pState == Player.STATE_BUFFERING -> PlaybackState.Buffering
            pState == Player.STATE_READY -> PlaybackState.Paused
            pState == Player.STATE_ENDED -> PlaybackState.Ended
            else -> PlaybackState.Idle
        }
    }

    /**
     * Force HLS via [HlsMediaSource] for .m3u8 URLs — Media3 auto-detection
     * fails on some providers that omit the right content-type. Everything
     * else (MP4/TS/AAC streams) uses the default auto-detect path.
     */
    private fun buildMediaSource(url: String): MediaSource {
        val item = MediaItem.fromUri(Uri.parse(url))
        if (!url.contains(".m3u8", ignoreCase = true)) {
            // Progressive/auto-detect for MP4/TS/AAC streams.
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(com.mytv.app.data.HttpAgent.UA)
                .setDefaultRequestProperties(com.mytv.app.data.HttpAgent.STREAM_HEADERS)
                .setAllowCrossProtocolRedirects(true)
            return ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(com.mytv.app.data.HttpAgent.UA)
            .setDefaultRequestProperties(com.mytv.app.data.HttpAgent.STREAM_HEADERS)
            .setAllowCrossProtocolRedirects(true)
        return HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(item)
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

/** Stable, observable playback states for the UI to render. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Buffering : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data object Ended : PlaybackState
    data class Error(val message: String) : PlaybackState
}
