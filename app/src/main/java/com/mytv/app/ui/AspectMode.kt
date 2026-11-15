package com.mytv.app.ui

import androidx.media3.common.C
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * How the video is fitted into whatever window it is drawn in — the phone screen
 * or the surface Android Auto hands the projected activity.
 *
 * Live SD feeds are frequently anamorphic (720x576 with a 4:3 or 16:9 sample
 * aspect) and providers often strip that metadata, so the player can end up
 * stretching or cropping the picture. "Auto" trusts the stream; 16:9 / 4:3 force
 * a fixed frame; Zoom fills and crops; Stretch fills without preserving aspect.
 *
 * Everything here is size-agnostic on purpose: callers pass in the size of the
 * display (phone window, car surface) and the stream, and [applyTo] letterboxes
 * inside it. media3's AspectRatioFrameLayout treats ratio 0 as "unknown" and
 * stretches to the view size, so a known size must never resolve to 0.
 */
enum class AspectMode(
    val label: String,
    val resize: Int,        // AspectRatioFrameLayout.RESIZE_MODE_*
    val forcedRatio: Float, // 0 = follow the stream's own aspect
) {
    AUTO("Auto", AspectRatioFrameLayout.RESIZE_MODE_FIT, 0f),
    R169("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 16f / 9f),
    R43("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIT, 4f / 3f),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, 0f),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL, 0f);

    /** Fit [pv]'s surface to this mode for a stream of [size] (null = unknown yet). */
    fun applyTo(pv: PlayerView, size: VideoSize?) {
        val ratio = if (forcedRatio > 0f) forcedRatio else ratioOf(size)
        runCatching {
            pv.resizeMode = resize
            pv.findViewById<AspectRatioFrameLayout?>(androidx.media3.ui.R.id.exo_content_frame)
                ?.setAspectRatio(ratio)
        }
        runCatching {
            (pv.player as? ExoPlayer)?.setVideoScalingMode(
                if (resize == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                    C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                } else {
                    C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                }
            )
        }
    }

    companion object {
        /** Stored preference value -> mode (unknown/legacy names fall back to Auto). */
        fun from(stored: String?): AspectMode =
            entries.firstOrNull { it.name == stored } ?: AUTO

        /** Frame aspect for a stream size, honouring its sample aspect (0 = unknown). */
        fun ratioOf(size: VideoSize?): Float =
            if (size == null || size.width <= 0 || size.height <= 0) 0f
            else size.width * size.pixelWidthHeightRatio / size.height
    }
}
