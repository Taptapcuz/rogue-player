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
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import com.rogueplayer.R
import com.rogueplayer.player.PlaybackService
import com.rogueplayer.player.LiveRecorder
import com.rogueplayer.player.VlcEngine
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
    private var recBtn: TextView? = null

    /** libVLC path (Settings → Player engine = VLC). Null when ExoPlayer is in use. */
    private var vlc: VlcEngine? = null
    private var vlcSurface: SurfaceView? = null
    private var playPauseBtn: TextView? = null

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

        // ---- engine routing -------------------------------------------------
        // VLC is phone-only: the car path needs ExoPlayer's media-session/audio-attribute
        // wiring or Android Auto plays picture with no sound.
        val useVlc = UiSettings.engine(this) == UiSettings.ENGINE_VLC
        if (useVlc) {
            playerView?.visibility = View.GONE
            startVlcEngine()
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

        // One-tap recording of the live channel: HLS segments are appended straight to a
        // .ts file, so it costs no CPU and the picture is untouched. The label doubles as
        // the elapsed-time readout and the stop button.
        recBtn = findViewById(R.id.player_rec)
        // Recording is a live-only affordance: the car/phone player for a movie or episode is
        // the same screen as Live TV minus REC. Live channels are HLS (.m3u8); VOD is
        // .mkv/.mp4 and has no sliding segment window to copy.
        if (!lastUrl.contains(".m3u8", ignoreCase = true)) recBtn?.visibility = View.GONE
        recBtn?.setOnClickListener {
            if (LiveRecorder.isRecording()) {
                LiveRecorder.stop()
            } else {
                LiveRecorder.start(this, lastUrl, lastTitle.ifBlank { "recording" }) { file, error ->
                    runOnUiThread {
                        renderRec()
                        val message = when {
                            file != null && error != null -> "Recording stopped after a stream error — saved ${file.name}"
                            file != null -> "Saved ${file.name} (${file.length() / 1_048_576} MB)"
                            else -> error ?: "Nothing recorded"
                        }
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                renderRec()
                startRecTicker()
            }
        }

        // Close returns to the browse screen.
        findViewById<View>(R.id.player_close).setOnClickListener { finish() }

        // Retry replays the same source.
        findViewById<View>(R.id.player_retry).setOnClickListener {
            if (lastUrl.isNotBlank()) {
                if (vlc != null) vlc?.play(lastUrl) else svc?.play(lastUrl, lastTitle.ifBlank { "MYTV" })
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
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val bar = topBar
            val onChrome = bar != null && bar.visibility == View.VISIBLE &&
                ev.y >= bar.top && ev.y <= bar.bottom && ev.x >= bar.left && ev.x <= bar.right
            // A tap that lands on the bar's own controls (REC / aspect / close) must reach
            // the button. This used to toggle the chrome off on ACTION_UP, i.e. hide the bar
            // out from under the finger before the click was dispatched — which is why a tap
            // on REC started a recording but a second tap never stopped it.
            if (onChrome) showChrome() else toggleChrome()
        }
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
        // Recording follows the player: leaving the player finalises the .ts file.
        if (isFinishing && LiveRecorder.isRecording()) LiveRecorder.stop()
        runCatching { vlc?.release() }
        vlc = null
        recBtn?.removeCallbacks(recTicker)
        runCatching { unbindService(connection) }
        super.onDestroy()
    }


    /**
     * libVLC path: a SurfaceView under the existing chrome, plus one play/pause button in the
     * top bar (media3's controller can't drive VLC, so the transport control is ours).
     */
    private fun startVlcEngine() {
        val root = findViewById<FrameLayout>(R.id.player_root) ?: return
        if (lastUrl.isBlank()) return

        val surfaceView = SurfaceView(this).apply {
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(surfaceView, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Smaller than the video it overlays: put the new view directly above player_view.
        (surfaceView.layoutParams as FrameLayout.LayoutParams)

        val engine = VlcEngine(this)
        engine.onState = { state -> runOnUiThread { renderVlc(state) } }
        engine.attach(surfaceView)
        engine.play(lastUrl)

        vlc = engine
        vlcSurface = surfaceView

        // Transport button in the top bar, before the aspect control.
        val bar = findViewById<View>(R.id.player_top_bar) as? android.view.ViewGroup
        playPauseBtn = TextView(this).apply {
            text = "\u23F8"
            textSize = 16f
            setTextColor(Color.parseColor("#F3F5FF"))
            setBackgroundColor(Color.parseColor("#1D1D26"))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener {
                vlc?.toggle()
                renderVlc(null)
            }
        }
        bar?.addView(playPauseBtn, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
    }

    /** Keep the VLC transport glyph honest; [state] null means "re-read the engine". */
    private fun renderVlc(state: VlcEngine.State?) {
        val btn = playPauseBtn ?: return
        val playing = when (state) {
            VlcEngine.State.PLAYING -> true
            VlcEngine.State.PAUSED -> false
            null -> vlc?.isPlaying() == true
            else -> return
        }
        btn.text = if (playing) "\u23F8" else "\u25B6"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** REC button label: idle glyph, or elapsed time in red while recording. */
    private fun renderRec() {
        val btn = recBtn ?: return
        if (LiveRecorder.isRecording()) {
            val secs = LiveRecorder.elapsedMs() / 1000
            btn.text = String.format(Locale.US, "⏹ %d:%02d", secs / 60, secs % 60)
            btn.setTextColor(Color.parseColor("#FF5A4F"))
        } else {
            btn.text = "⏺ REC"
            btn.setTextColor(Color.parseColor("#F3F5FF"))
        }
    }

    private fun startRecTicker() {
        recBtn?.removeCallbacks(recTicker)
        recBtn?.postDelayed(recTicker, 1000)
    }

    private val recTicker = object : Runnable {
        override fun run() {
            renderRec()
            if (LiveRecorder.isRecording()) recBtn?.postDelayed(this, 1000)
        }
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
