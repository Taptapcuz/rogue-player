package com.rogueplayer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * libVLC playback engine — the optional second engine behind Settings → Player engine.
 *
 * Why it exists: this provider's VOD is MKV/HEVC and some streams carry AC3/E-AC3 or odd HLS
 * headers that ExoPlayer refuses. VLC decodes those itself (including software fallback).
 *
 * Deliberately phone-only for now: the Android Auto path stays on ExoPlayer because AA plays
 * picture-with-no-sound unless audio is published as media with explicit AudioAttributes and
 * an ACTIVE MediaSession — ExoPlayer gets that from the service, VLC does not, and shipping a
 * silent car is worse than not supporting a codec there.
 *
 * VLC owns its own audio output and its own surface attachment, so this class is self-contained:
 * point it at a [SurfaceView], give it a URL, and it plays.
 */
class VlcEngine(context: Context) {

    private val libVlc: LibVLC = LibVLC(
        context,
        arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-time-stretch",
            // Live HLS from this panel: 1.5 s of buffer avoids the stutter ExoPlayer masked.
            "--network-caching=1500",
        )
    )

    val player: MediaPlayer = MediaPlayer(libVlc)

    /** Reported on the main thread; mirrors the states the phone UI cares about. */
    var onState: ((State) -> Unit)? = null

    enum class State { IDLE, PLAYING, PAUSED, ENDED, ERROR }

    private var currentUrl: String? = null

    init {
        player.setEventListener { event ->
            val state = when (event.type) {
                MediaPlayer.Event.Playing -> State.PLAYING
                MediaPlayer.Event.Paused -> State.PAUSED
                MediaPlayer.Event.EndReached -> State.ENDED
                MediaPlayer.Event.EncounteredError -> State.ERROR
                else -> null
            }
            state?.let { onState?.invoke(it) }
            if (event.type == MediaPlayer.Event.EncounteredError) {
                Log.w(TAG, "vlc error on ${currentUrl?.take(80)}")
            }
        }
    }

    /**
     * Attach the view video is drawn into.
     *
     * MUST be [VLCVideoLayout] through `MediaPlayer.attachViews(...)`, not a bare
     * `SurfaceView` via `vlcVout.setVideoView`: the latter never gets a usable window
     * ("libvlc window: request N not implemented") and the screen stays pure black even
     * though the decoder is running (logged `output: 1920x1040`).
     */
    fun attach(layout: VLCVideoLayout) {
        runCatching {
            player.attachViews(layout, null, false, false)
            // Fill the screen the way the ExoPlayer path does (PlayerView resize_mode=fit).
            player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }.onFailure { Log.w(TAG, "attach failed", it) }
    }

    fun play(url: String) {
        if (url.isBlank()) return
        currentUrl = url
        runCatching {
            val media = Media(libVlc, Uri.parse(url))
            // Hardware first with software fallback — VLC flips automatically when a codec
            // is unsupported, which is the whole reason this engine is here.
            media.setHWDecoderEnabled(true, false)
            // This panel resets connections from non-browser user agents (VLC/3.x is refused
            // outright: "HTTP connection failure", "Connection reset by peer"). Same UA the
            // ExoPlayer path sends, or nothing plays.
            media.addOption(":http-user-agent=" + com.rogueplayer.data.HttpAgent.UA)
            media.addOption(":http-referrer=")
            player.media = media
            media.release()
            player.play()
        }.onFailure {
            Log.w(TAG, "play failed", it)
            onState?.invoke(State.ERROR)
        }
    }

    fun isPlaying(): Boolean = runCatching { player.isPlaying }.getOrDefault(false)

    /** Map the app's aspect modes onto VLC's own scale types. */
    fun videoScale(scale: MediaPlayer.ScaleType) {
        runCatching { player.videoScale = scale }
    }

    fun positionMs(): Long = runCatching { player.time }.getOrDefault(0L).coerceAtLeast(0L)

    fun lengthMs(): Long = runCatching { player.length }.getOrDefault(0L).coerceAtLeast(0L)

    fun canSeek(): Boolean = runCatching { player.isSeekable }.getOrDefault(false)

    fun seekTo(ms: Long) {
        runCatching { player.setTime(ms, true) }
    }

    fun toggle() {
        runCatching {
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun pause() = runCatching { player.pause() }.let { }

    fun stop() = runCatching { player.stop() }.let { }

    fun release() {
        runCatching {
            player.stop()
            runCatching { player.detachViews() }
            player.release()
            libVlc.release()
        }
    }

    private companion object {
        const val TAG = "VlcEngine"
    }
}
