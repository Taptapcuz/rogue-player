package com.rogueplayer.player

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /** Classic media session — reliable, explicitly-active token for Android Auto's
     *  audio-focus routing (Media3's auto-active is notification-driven and flaky
     *  from a car session with no foreground service). */
    private var classicSession: android.media.session.MediaSession? = null

    /** What is currently loaded, so a decoder failure can be re-prepared. */
    private var currentUrl: String? = null
    private var currentSurface: android.view.Surface? = null
    /** True when the last surface detach paused the player — the next surface resumes it. */
    private var pausedForDetach = false

    /** How many times this stream has been rebuilt after a decoder failure. */
    private var recoveryAttempts = 0

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
            // A dead video decoder is recoverable: rebuild the player and re-prepare
            // the same stream instead of freezing on the last frame (the head unit
            // symptom). Anything else (source/network) stays a real error.
            if (recoverFromCodecError(error)) return
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

    fun getPlayer(): ExoPlayer = ensurePlayer()

    /**
     * A/V offset in ms: >0 holds the audio back by that much (the picture leads it by
     * fewer/zero ms), used when the car's audio path outruns its video path. 0 = the
     * untouched default player, no audio processor installed.
     */
    var audioDelayMs: Int
        get() = avPrefs.getInt(KEY_AV_DELAY, 0).coerceIn(0, MAX_AV_DELAY)
        private set(value) {
            avPrefs.edit().putInt(KEY_AV_DELAY, value.coerceIn(0, MAX_AV_DELAY)).apply()
        }

    private val avPrefs by lazy { getSharedPreferences("mytv_playback", MODE_PRIVATE) }

    /**
     * Change the offset and rebuild the player so the new sink takes effect. The
     * stream restarts at its current position — the offset only exists at sink level,
     * so there is no way to retune it in place.
     */
    fun setAudioDelay(ms: Int) {
        val next = ms.coerceIn(0, MAX_AV_DELAY)
        if (next == audioDelayMs) return
        audioDelayMs = next
        val url = currentUrl
        val title = currentTitle
        val pos = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        val wasPlaying = runCatching { player?.isPlaying == true }.getOrDefault(false)
        runCatching { player?.release() }
        player = null
        val fresh = ensurePlayer()
        if (!url.isNullOrBlank()) {
            fresh.setMediaSource(buildMediaSource(url))
            fresh.prepare()
            if (pos > 0) fresh.seekTo(pos)
            fresh.playWhenReady = true
            Log.i(TAG, "audio delay ${next}ms applied (restarted $title)")
        }
    }

    /**
     * Build (or reuse) the player. [softwareVideo] restricts video decoding to
     * software codecs — the escape hatch after the hardware decoder (QTI `c2.qti.*`)
     * dies on a stream it cannot handle.
     */
    private fun ensurePlayer(softwareVideo: Boolean = false): ExoPlayer {
        player?.let { return it }
        val delay = audioDelayMs
        val renderers = DelayRenderersFactory(this, delay).setEnableDecoderFallback(true)
        if (softwareVideo) {
            renderers.setMediaCodecSelector { mimeType, secure, tunneling ->
                val all = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
                all.filter { it.softwareOnly }.ifEmpty { all }
            }
        }
        val newPlayer = ExoPlayer.Builder(this, renderers)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true)
            .build()
        newPlayer.addListener(stateListener)
        observers.forEach { newPlayer.addListener(it) }
        player = newPlayer
        // MediaSession powers lock-screen / media notification controls.
        mediaSession = MediaSession.Builder(this, newPlayer).build()
        // Classic active session so Android Auto routes the audio to the car
        // instead of the phone. (The old pin-to-phone-speaker kept the car silent —
        // that is deliberately gone so sound comes out the head unit.)
        ensureClassicSession(newPlayer)
        updateSessionActive()
        // A rebuilt player must keep rendering onto the surface the car handed us.
        currentSurface?.let { surface -> runCatching { newPlayer.setVideoSurface(surface) } }
        return newPlayer
    }

    /** Build (once) the classic, explicitly-active media session for AA routing. */
    private fun ensureClassicSession(p: Player) {
        if (classicSession != null) return
        val s = android.media.session.MediaSession(this, "RoguePlayer")
        s.setFlags(
            android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { p.play() }
            override fun onPause() { p.pause() }
            override fun onStop() { stop() }
            override fun onSeekTo(pos: Long) { p.seekTo(pos) }
            override fun onSkipToNext() { p.seekToDefaultPosition() }
        })
        classicSession = s
    }

    /** Keep the classic session's active flag + metadata in sync with the player. */
    private fun updateSessionActive() {
        val p = player ?: return
        val active = p.isPlaying
        runCatching { classicSession?.isActive = active }
        runCatching {
            if (active) {
                classicSession?.setMetadata(
                    android.media.MediaMetadata.Builder()
                        .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                        .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, currentTitle)
                        .build())
            }
        }
    }

    /**
     * True when [error] is a video-decoder failure worth rebuilding for.
     * Media3 reports a dead MediaCodec as `ERROR_CODE_DECODING_FAILED` /
     * `ERROR_CODE_DECODER_INIT_FAILED` with a `MediaCodecVideoRenderer error`
     * message; source/network failures are left alone.
     */
    private fun isCodecError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
        ) return true
        val text = (error.message ?: "") + " " + (error.cause?.toString() ?: "")
        return text.contains("MediaCodecVideoRenderer", true) ||
            text.contains("Decoder failed", true) ||
            text.contains("MediaCodec", true) && text.contains("error", true)
    }

    /**
     * Rebuild the player after a decoder failure and resume the same stream at the
     * position it died. First attempt keeps hardware decoding (a stream glitch is
     * usually transient); the second forces software video.
     */
    private fun recoverFromCodecError(error: PlaybackException): Boolean {
        val url = currentUrl ?: return false
        if (!isCodecError(error)) return false
        if (recoveryAttempts >= MAX_CODEC_RECOVERIES) return false

        // Never rebuild inside the error callback: releasing and recreating an ExoPlayer
        // on the main thread can take seconds, and the car host's own binder calls (the
        // surface callback in particular) then miss their timeout, which the head unit
        // reports as "MYTV isn't responding" / `ANR API: ON_SURFACE_AVAILABLE`.
        // Bump the attempt counter now so a burst of errors cannot queue several
        // rebuilds, then do the work once the callback has returned.
        recoveryAttempts++
        val attempt = recoveryAttempts
        mainHandler.postDelayed({ performCodecRecovery(attempt) }, 400L)
        return true
    }

    /** The actual rebuild, run off the error callback's stack. */
    private fun performCodecRecovery(attempt: Int) {
        val url = currentUrl ?: return
        val position = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        val software = attempt >= 2
        Log.w(TAG, "video decoder failed; rebuilding player attempt=$attempt software=$software url=$url")

        val old = player
        player = null
        runCatching { mediaSession?.release() }
        mediaSession = null
        runCatching { old?.release() }

        state = PlaybackState.Buffering
        val fresh = ensurePlayer(softwareVideo = software)
        fresh.setMediaSource(buildMediaSource(url))
        fresh.prepare()
        // A live stream moves on while the decoder is rebuilt, so the old position can
        // fall outside the window (or its segment is gone) — seeking into that gap is
        // what turned the first retry into `ERROR_CODE_DECODER_INIT_FAILED`. Live
        // streams restart at the live edge; only VOD/catch-up resumes where it died.
        val live = runCatching { fresh.isCurrentMediaItemLive }.getOrDefault(false)
        if (live) {
            Log.w(TAG, "live stream — restarting at the live edge, not $position ms")
            fresh.seekToDefaultPosition()
        } else if (position > 0) {
            fresh.seekTo(position)
        }
        fresh.playWhenReady = true
    }

    fun play(url: String, title: String) = play(url, title, 0L)
    fun play(url: String, title: String, positionMs: Long) {
        currentTitle = title
        currentUrl = url
        recoveryAttempts = 0
        state = PlaybackState.Buffering
        val p = getPlayer()
        p.setMediaSource(buildMediaSource(url))
        p.prepare()
        if (positionMs > 0) p.seekTo(positionMs)
        p.playWhenReady = true
        updateSessionActive()
    }

    fun playNow() {
        player?.playWhenReady = true
        refreshState()
        updateSessionActive()
    }

    /**
     * Route this app's USAGE_MEDIA audio to the phone's own speaker.
     *
     * Android Auto routes a navigation-category app's audio to the (often silent) nav
     * channel, so live TV and the car YouTube screen go mute in the car. Pinning the
     * media stream to the built-in speaker plays it locally — video stays on the head
     * unit, sound comes out the phone. API 33+; the call is @SystemApi and absent from
     * the app SDK, so it is invoked reflectively. Fully guarded: on any failure the
     * preference is simply not set and playback keeps its default routing. Verified in
     * the car for both live TV and the car-side YouTube screen.
     */
    private fun preferPhoneSpeaker() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        runCatching {
            val devCls = Class.forName("android.media.AudioDeviceAttributes")
            val device = devCls.getConstructor(Int::class.javaPrimitiveType, String::class.java)
                .newInstance(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
            val method = android.media.AudioManager::class.java.getMethod(
                "setPreferredDeviceForStrategy",
                android.media.AudioAttributes::class.java,
                devCls,
            )
            method.invoke(am, attrs, device)
            Log.i(TAG, "media audio preferred on phone speaker")
        }.onFailure { Log.w(TAG, "phone-speaker route unavailable", it) }
    }

    fun pause() {
        player?.playWhenReady = false
        refreshState()
    }

    /**
     * Render the player's video onto a surface the car host owns (Android Auto hands
     * a navigation app one drawable Surface). Passing null detaches it again.
     */
    fun setVideoSurface(surface: android.view.Surface?) {
        currentSurface = surface
        val p = player
        if (p == null) return
        if (surface == null) {
            // Detach only after pausing: clearing the surface mid-render makes the
            // codec flush an already-released instance
            // (`flush() is valid only at Executing states; currently at Released state`).
            //
            // Pausing is a codec-safety measure, not an instruction to stop: moving
            // between two pages that both have a picture (guide rail → full screen,
            // full screen → rail) detaches one surface and attaches another. Remember
            // whether we were playing so the next surface can put it back, otherwise
            // every page change silently left the stream paused.
            pausedForDetach = p.playWhenReady
            runCatching { p.pause() }
            runCatching { p.setVideoSurface(null) }
            // Leaving the video screen ends this viewing session, so a later ▶ Stream gets
            // a fresh budget of decoder retries instead of inheriting an exhausted one.
            recoveryAttempts = 0
            return
        }
        runCatching { p.setVideoSurface(surface) }
        // Hand the picture to the new surface with its playback intent intact (see the
        // detach above). Only the pause we caused ourselves is undone here — a stream
        // the user paused stays paused.
        if (pausedForDetach) {
            pausedForDetach = false
            runCatching {
                p.playWhenReady = true
            }
        }
        // Coming back from the channel list ("Stream"): the stream may have died while the
        // video screen was gone — decoder retries exhausted, or the playlist was stopped —
        // and the player then hands the host a black pane with no way back. Re-prepare
        // instead of attaching a dead player.
        val url = currentUrl
        val dead = state is PlaybackState.Error || p.playbackState == Player.STATE_IDLE
        if (url != null && dead) {
            recoveryAttempts = 0
            Log.w(TAG, "surface re-attached to a dead player — re-preparing $url")
            runCatching {
                p.setMediaSource(buildMediaSource(url))
                p.prepare()
                p.playWhenReady = true
            }
            state = PlaybackState.Buffering
        }
    }

    /**
     * Detach a surface only if it is still the one attached.
     *
     * A view that is being torn down posts this from its TextureView destroy
     * callback, and that callback lands asynchronously — often AFTER the next page
     * has already attached its own surface. Unconditional detaching then pauses the
     * stream the new page just started (the "go back from full screen and the rail
     * is paused" bug). With the identity check a dying view can only detach what it
     * still owns, so a stale page can never pause a live one.
     */
    fun detachVideoSurface(surface: android.view.Surface?) {
        if (surface == null || currentSurface !== surface) {
            Log.i(TAG, "ignored stale surface detach (a newer surface is attached)")
            return
        }
        setVideoSurface(null)
    }

    fun stop() {
        Log.d("MYTVPlay", "stop()")
        player?.let { exo ->
            exo.stop()
            exo.clearMediaItems()
        }
        currentUrl = null
        currentTitle = ""
        recoveryAttempts = 0
        pausedForDetach = false
        state = PlaybackState.Idle
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
        classicSession?.release()
        classicSession = null
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
        updateSessionActive()
    }

    /**
     * Build the media source for a URL. .m3u8 routes through [HlsMediaSource]
     * when "Force HLS" is on (the default — Media3 auto-detection fails on some
     * providers that omit the right content-type); everything else uses Media3's
     * default auto-detect (MP4/TS/AAC/m3u8 when not forced).
     */
    /** Shared OkHttp client for stream IO (redirects + connection reuse). */
    private val streamClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun buildMediaSource(url: String): MediaSource {
        val uri = Uri.parse(url)
        val item = MediaItem.fromUri(uri)
        val isHls = url.contains(".m3u8", ignoreCase = true)
        // VOD on this provider 302s every request to another host (a plain IP), and Media3's
        // DefaultHttpDataSource (HttpURLConnection) surfaced that as
        // "SocketException: Connection reset" on open — so nothing in Movies/TV Shows would
        // play. OkHttp handles the redirect hop and reuses connections properly.
        val httpFactory = OkHttpDataSource.Factory(streamClient)
            .setUserAgent(com.rogueplayer.data.HttpAgent.UA)
            .setDefaultRequestProperties(com.rogueplayer.data.HttpAgent.STREAM_HEADERS)
        return when {
            isHls && com.rogueplayer.ui.UiSettings.forceHls(this) ->
                HlsMediaSource.Factory(httpFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item)
            // A local file (the car's Videos page plays the phone's own videos): the
            // OkHttp-only factory threw "Malformed URL" on content:// URIs, so local
            // sources go through DefaultDataSource, which resolves content/file itself.
            uri.scheme == "content" || uri.scheme == "file" ->
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
                    .createMediaSource(item)
            else -> DefaultMediaSourceFactory(httpFactory).createMediaSource(item)
        }
    }

    companion object {
        const val ACTION_PLAY = "com.rogueplayer.PLAY"
        const val ACTION_PAUSE = "com.rogueplayer.PAUSE"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"

        /** Rebuild attempts after a decoder failure: hardware first, then software. */
        private const val MAX_CODEC_RECOVERIES = 2
        private const val TAG = "MYTVPlay"

        /** Stored A/V offset (ms) and its ceiling — see [setAudioDelay]. */
        private const val KEY_AV_DELAY = "av_delay_ms"
        private const val MAX_AV_DELAY = 500

        fun toPlaybackActions(intent: Intent): String? = intent.action
    }

    /**
     * Renderers factory with the A/V offset baked into the audio sink. With a delay of
     * 0 it returns the stock sink, so the default path is exactly what it was.
     */
    private class DelayRenderersFactory(
        context: android.content.Context,
        private val delayMs: Int,
    ) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: android.content.Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): androidx.media3.exoplayer.audio.AudioSink {
            if (delayMs <= 0) {
                return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)!!
            }
            val chain = androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                *arrayOf<androidx.media3.common.audio.AudioProcessor>(AudioDelayProcessor(delayMs)),
            )
            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioProcessorChain(chain)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
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
