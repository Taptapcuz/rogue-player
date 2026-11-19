package com.rogueplayer.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.rogueplayer.R
import com.rogueplayer.player.PlaybackService
import com.rogueplayer.player.PlaybackState

/**
 * Fullscreen phone player. Binds to [PlaybackService] so it shares the same
 * ExoPlayer as the car projection, shows an overlay with the channel title,
 * buffering spinner and error/retry, and exposes media3's controller (play /
 * pause / seek) on tap.
 */
class PlayerActivity : AppCompatActivity() {

    private var svc: PlaybackService? = null
    private var playerView: PlayerView? = null
    private var bufferingBar: ProgressBar? = null
    private var errorPane: View? = null
    private var errorText: TextView? = null
    private var titleText: TextView? = null

    private var lastUrl: String = ""
    private var lastTitle: String = ""

    /**
     * Picture geometry lives in [AspectMode] so the phone screen and the car
     * projection apply exactly the same rules. Persisted across restarts.
     */
    private var aspect = AspectMode.AUTO
    private var aspectBtn: TextView? = null
    private var topBar: View? = null

    /**
     * The title/aspect bar used to sit over the video permanently, which read as
     * a grey band across the top. It now behaves like a normal player overlay:
     * fades out after a few seconds, comes back on tap.
     */
    private val hideChromeRunnable = Runnable {
        topBar?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            topBar?.visibility = View.GONE
        }?.start()
    }

    private fun showChrome() {
        val bar = topBar ?: return
        bar.removeCallbacks(hideChromeRunnable)
        bar.visibility = View.VISIBLE
        bar.alpha = 1f
        bar.postDelayed(hideChromeRunnable, 3500)
    }

    private fun hideChrome() {
        val bar = topBar ?: return
        bar.removeCallbacks(hideChromeRunnable)
        bar.animate().alpha(0f).setDuration(180).withEndAction {
            bar.visibility = View.GONE
        }.start()
    }

    private fun toggleChrome() {
        if (topBar?.visibility == View.VISIBLE) hideChrome() else showChrome()
    }

    /** Fullscreen: no status/nav bar drawing a band over the picture. */
    private fun applyImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private val serviceListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = render()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = render()
        override fun onIsPlayingChanged(isPlaying: Boolean) = render()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = render()
        // Re-assert the chosen geometry whenever the stream changes size (channel switch).
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) =
            applyAspect(videoSize)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder ?: return
            svc = binder.getService()
            playerView?.player = binder.getService().getPlayer()
            svc?.attach(serviceListener)
            applyAspect()
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc?.detach(serviceListener)
            svc = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.statusBarColor = Color.parseColor("#0B0B10")
        window.navigationBarColor = Color.parseColor("#0B0B10")
        UiSettings.applyBrightness(this)

        lastUrl = intent?.getStringExtra(EXTRA_URL).orEmpty()
        lastTitle = intent?.getStringExtra(EXTRA_TITLE).orEmpty()

        playerView = findViewById(R.id.player_view)
        bufferingBar = findViewById(R.id.player_buffering)
        errorPane = findViewById(R.id.player_error)
        errorText = findViewById(R.id.player_error_text)
        titleText = findViewById(R.id.player_title)

        if (titleText != null && lastTitle.isNotBlank()) {
            titleText?.text = lastTitle
        }

        // Aspect / geometry: Auto (stream), 16:9, 4:3, Zoom, Stretch. Persisted.
        aspectBtn = findViewById(R.id.player_aspect)
        aspect = AspectMode.from(prefs().getString("aspect", AspectMode.AUTO.name))
        aspectBtn?.setOnClickListener { showAspectMenu() }
        applyAspect()

        // Fullscreen chrome: hidden system bars, and the title/aspect bar fades
        // out on its own instead of sitting permanently over the picture.
        applyImmersive()
        val bar = findViewById<View>(R.id.player_top_bar)
        topBar = bar
        val basePad = bar.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, basePad + top, v.paddingRight, v.paddingBottom)
            insets
        }
        // Chrome visibility is driven by touches + a 3.5s auto-hide (see
        // dispatchTouchEvent); start visible, then let it fall away.
        showChrome()
        topBar?.postDelayed(hideChromeRunnable, 2500)

        // Close returns to the browse screen.
        findViewById<View>(R.id.player_close).setOnClickListener { finish() }

        // Retry replays the same source.
        findViewById<View>(R.id.player_retry).setOnClickListener {
            if (lastUrl.isNotBlank()) {
                svc?.play(lastUrl, lastTitle.ifBlank { "MYTV" })
            }
        }

        bindService(
            Intent(this, PlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        // Re-attach the surface: the guide's preview may have taken it over.
        svc?.getPlayer()?.let { p ->
            playerView?.player = null
            playerView?.player = p
        }
        svc?.let { playerView?.player = it.getPlayer() }
        applyImmersive()
    }

    /**
     * Every touch is seen here first, so a tap anywhere (including on the bar
     * itself, which is clickable and would otherwise swallow it) shows or hides
     * the chrome. The bar also hides itself 3.5s after it appears, which is what
     * makes it impossible to get "stuck".
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) toggleChrome()
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onDestroy() {
        // Back out of the fullscreen player and the stream stops — it used to keep
        // playing audio behind the channel list, which reads as a bug. The guide
        // preview passes EXTRA_KEEP_STREAM so going fullscreen from there and
        // coming back leaves its preview alive.
        val keep = intent?.getBooleanExtra(EXTRA_KEEP_STREAM, false) == true
        if (isFinishing && !keep) runCatching { svc?.stop() }
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("mytv_player", MODE_PRIVATE)

    /** Push the current [aspect] onto the view (and the player, once bound). */
    private fun applyAspect(size: androidx.media3.common.VideoSize? = null) {
        val pv = playerView ?: return
        val video = size ?: runCatching { svc?.getPlayer()?.videoSize }.getOrNull()
        aspect.applyTo(pv, video)
        pv.post {
            val frame = pv.findViewById<AspectRatioFrameLayout?>(androidx.media3.ui.R.id.exo_content_frame)
            android.util.Log.d(
                "PlayerAspect",
                "mode=${aspect.label} video=${video?.width}x${video?.height}@${video?.pixelWidthHeightRatio} " +
                    "view=${pv.width}x${pv.height} frame=${frame?.width}x${frame?.height}",
            )
        }
        aspectBtn?.text = aspect.label
    }

    /** Radio list of geometry modes — faster than cycling through five options. */
    private fun showAspectMenu() {
        val modes = AspectMode.entries
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Aspect ratio")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(aspect)) { d, which ->
                aspect = modes[which]
                prefs().edit().putString("aspect", aspect.name).apply()
                applyAspect()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val service = svc ?: return
        val pv = playerView ?: return

        // The service owns the player; if it was released (service torn down while
        // the screen is animating away) touching it would throw — bail out instead.
        val player = runCatching { service.getPlayer() }.getOrNull() ?: return

        // Title always reflects the loaded source.
        titleText?.text = service.currentTitle.ifBlank { lastTitle.ifBlank { "MYTV" } }

        when (service.state) {
            is PlaybackState.Buffering -> {
                bufferingBar?.visibility = View.VISIBLE
                errorPane?.visibility = View.GONE
            }
            is PlaybackState.Error -> {
                bufferingBar?.visibility = View.GONE
                errorPane?.visibility = View.VISIBLE
                errorText?.text = (service.state as PlaybackState.Error).message
            }
            else -> {
                bufferingBar?.visibility = View.GONE
                errorPane?.visibility = View.GONE
            }
        }

        // Keep the controller usable; hide when error is showing.
        pv.useController = service.state !is PlaybackState.Error
        if (service.state is PlaybackState.Error) {
            pv.player = null
        } else if (pv.player !== player) {
            pv.player = player
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        /** Set when the caller (the guide preview) keeps the stream running. */
        const val EXTRA_KEEP_STREAM = "keep_stream"

        fun intent(
            context: Context,
            url: String,
            title: String,
            keepStreamOnBack: Boolean = false,
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KEEP_STREAM, keepStreamOnBack)
    }
}
