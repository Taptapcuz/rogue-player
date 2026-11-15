package com.mytv.app.car

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mytv.app.R
import com.mytv.app.player.PlaybackService
import com.mytv.app.ui.AspectMode

/**
 * The projection activity. On the phone this is the fullscreen player; on an
 * Android Auto host it is launched into the car task (see manifest
 * taskAffinity com.mytv.app.car) so the same ExoPlayer surface renders on the
 * car screen — the projection route Android Auto's nav category allows.
 */
class ProjectionActivity : Activity() {

    private var player: ExoPlayer? = null
    private var svcRef: PlaybackService? = null
    private var playerView: PlayerView? = null
    private var bound = false
    private val title by lazy { intent?.getStringExtra("title") ?: "" }

    /** Fit the picture inside whatever window the car decided to give us. */
    private var mode = AspectMode.AUTO

    private val videoListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            playerView?.let { mode.applyTo(it, videoSize) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder ?: return
            val svc = binder.getService()
            svcRef = svc
            player = svc.getPlayer()
            playerView?.player = player
            svc.attach(videoListener)
            bound = true
            player?.let { p -> playerView?.let { pv -> mode.applyTo(pv, p.videoSize) } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // immersive fullscreen for the car surface
        WindowManager.LayoutParams.FLAG_FULLSCREEN.let {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setContentView(R.layout.activity_projection)
        playerView = findViewById(R.id.player_view)
        findViewById<TextView>(R.id.proj_title).also { it.text = if (title.isBlank()) "MYTV" else title }

        // Head units have no back button: this closes the projection (playback
        // survives in the service) so the car screen returns to the channel list.
        findViewById<TextView>(R.id.proj_back).setOnClickListener { finish() }

        mode = AspectMode.from(
            getSharedPreferences("mytv_player", MODE_PRIVATE).getString("aspect", AspectMode.AUTO.name)
        )
        // The head unit decides the projection surface size; log it so a wrong
        // aspect on a specific car can be diagnosed, and re-fit on every resize.
        val root = findViewById<View>(android.R.id.content)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            Log.i("MYTVCar", "projection window ${r - l}x${b - t} @${resources.displayMetrics.densityDpi}dpi (was ${orr - ol}x${ob - ot})")
            playerView?.let { pv -> mode.applyTo(pv, player?.videoSize) }
        }
        playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        bindService(
            Intent(this, PlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        // fade system bars
        window.decorView.setOnSystemUiVisibilityChangeListener { hideSystemUi() }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        runCatching { svcRef?.detach(videoListener) }
        svcRef = null
        if (bound) runCatching { unbindService(connection) }
        bound = false
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Car display rotated / resized: re-fit the picture to the new surface.
        playerView?.let { pv -> mode.applyTo(pv, player?.videoSize) }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }
}
