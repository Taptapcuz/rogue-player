package com.mytv.app.car

import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mytv.app.player.PlaybackService
import com.mytv.app.ui.AspectMode
import com.mytv.app.ui.Palette

/**
 * Draws the shared ExoPlayer onto the surface Android Auto hands us.
 *
 * This is the Fermata approach (`MirrorDisplay.setSurface`): the *host* decides
 * the geometry — [SurfaceContainer.width] x [SurfaceContainer.height] at
 * [SurfaceContainer.densityDpi] — and we create a [VirtualDisplay] of exactly
 * that size whose content is our own player [Presentation]. Nothing is assumed
 * to be 16:9; the video is fitted inside whatever box the head unit provides,
 * and the display is resized in place when the host reports a new size.
 */
class CarVideoSurface(private val carContext: CarContext) : SurfaceCallback {

    private val tag = "CarVideoSurface"

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var playerView: PlayerView? = null
    private var service: PlaybackService? = null
    private var mode = AspectMode.AUTO

    /** Latest geometry the car gave us (0 until the first surface arrives). */
    var lastWidth = 0
        private set
    var lastHeight = 0
        private set

    /** Channel name drawn in the corner of the car surface. */
    var title: String = ""
        set(value) {
            field = value
            titleView?.text = value
        }

    private var titleView: TextView? = null

    private val videoListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            playerView?.let { mode.applyTo(it, videoSize) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.getService() ?: return
            service = svc
            val pv = playerView
            if (pv != null) {
                pv.player = svc.getPlayer()
                svc.attach(videoListener)
                mode.applyTo(pv, svc.getPlayer().videoSize)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // ------------------------------------------------------- SurfaceCallback ---

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val w = container.width
        val h = container.height
        val dpi = container.dpi
        lastWidth = w
        lastHeight = h
        Log.i(tag, "car surface ${w}x$h @${dpi}dpi")

        val existing = virtualDisplay
        if (existing == null) {
            create(container)
        } else {
            // The head unit changed geometry (rotation, split view, different car):
            // resize the same display so the presentation re-lays out.
            runCatching { existing.resize(w, h, dpi) }
                .onFailure { teardown(); create(container) }
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        Log.i(tag, "car surface destroyed")
        teardown()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(tag, "visible area $visibleArea")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(tag, "stable area $stableArea")
    }

    // ------------------------------------------------------------- internals ---

    private fun create(container: SurfaceContainer) {
        val app = carContext.applicationContext
        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: run {
            Log.w(tag, "no DisplayManager")
            return
        }
        val vd = runCatching {
            dm.createVirtualDisplay(
                "mytv-car",
                container.width,
                container.height,
                container.dpi,
                container.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        }.getOrElse { t ->
            Log.e(tag, "VirtualDisplay failed", t)
            return
        }
        virtualDisplay = vd

        val display = vd.display
        val displayCtx = app.createDisplayContext(display)
        val pv = PlayerView(displayCtx).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(0xFF000000.toInt())
        }
        val root = FrameLayout(displayCtx).apply {
            setBackgroundColor(Palette.BG)
            addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                TextView(displayCtx).apply {
                    text = title
                    setTextColor(Palette.TEXT)
                    textSize = 13f
                    setPadding(24, 12, 24, 12)
                },
                FrameLayout.LayoutParams(
                    MATCH_PARENT, MATCH_PARENT,
                ).apply { gravity = Gravity.TOP or Gravity.START },
            )
        }
        titleView = root.getChildAt(1) as? TextView
        playerView = pv

        val pres = runCatching { Presentation(displayCtx, display).apply { setContentView(root) } }
            .getOrElse { t ->
                Log.e(tag, "Presentation failed", t)
                teardown()
                return
            }
        runCatching { pres.show() }
            .onFailure { t ->
                Log.e(tag, "show() failed", t)
                teardown()
                return
            }
        presentation = pres

        service?.let { svc ->
            pv.player = svc.getPlayer()
            mode.applyTo(pv, svc.getPlayer().videoSize)
        }

        // The presentation renders on its own display; the player lives in the
        // service, so bind to it for this surface's lifetime.
        if (service == null) {
            runCatching {
                app.bindService(
                    Intent(app, PlaybackService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }
        } else {
            service?.attach(videoListener)
            mode.applyTo(pv, service?.getPlayer()?.videoSize)
        }
        root.visibility = View.VISIBLE
    }

    private fun teardown() {
        runCatching { service?.detach(videoListener) }
        runCatching { presentation?.dismiss() }
        presentation = null
        playerView = null
        titleView = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        if (service != null) {
            runCatching {
                carContext.applicationContext.unbindService(connection)
            }
            service = null
        }
    }
}
