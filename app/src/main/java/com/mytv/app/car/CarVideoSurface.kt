package com.mytv.app.car

import android.graphics.Rect
import android.util.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * Forwards the surface Android Auto hands a navigation app straight to the player.
 *
 * The previous implementation mirrored a `Presentation` on a `VirtualDisplay` built
 * from that surface (the Fermata approach, which depends on their unpublished
 * auto-sdk). Under the Car App Library that fails twice:
 *
 *  - building the display, the views and the presentation inside
 *    `onSurfaceAvailable` blocks the main thread in a host binder callback, so the
 *    host reports `ANR API: ON_SURFACE_AVAILABLE` / "MYTV isn't responding";
 *  - the host reclaimed the surface ~180ms later while the app was busy.
 *
 * The supported shape (android/car-samples `navigation/SurfaceRenderer`) is to take
 * `SurfaceContainer.getSurface()` and draw on it directly. For video the equivalent
 * is `Player.setVideoSurface`, so this callback only records the geometry and hands
 * the surface over — no view creation, no display creation, nothing heavy.
 *
 * The host decides the box, so the picture fills it (no letterboxing); aspect modes
 * stay a phone-player feature.
 */
class CarVideoSurface(
    @Suppress("UNUSED_PARAMETER") carContext: CarContext,
    private val onSurface: (Surface?) -> Unit,
) : SurfaceCallback {

    private val tag = "CarVideoSurface"

    /** Latest geometry the car gave us (0 until the first surface arrives). */
    var lastWidth = 0
        private set
    var lastHeight = 0
        private set

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        lastWidth = container.width
        lastHeight = container.height
        Log.i(tag, "car surface ${container.width}x${container.height} @${container.dpi}dpi")
        onSurface(container.surface)
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        Log.i(tag, "car surface destroyed")
        // Never release the host's Surface — the host owns it.
        onSurface(null)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(tag, "visible area $visibleArea")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(tag, "stable area $stableArea")
    }
}
