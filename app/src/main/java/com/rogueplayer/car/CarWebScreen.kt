package com.rogueplayer.car

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * YouTube (or any web page) on the car screen — the CarStream trick, done with the
 * Car App Library instead of their unpublished auto-sdk.
 *
 * Mechanics: the host hands a navigation app a bare `Surface`. Rather than feed it
 * frames from a decoder (what [VideoScreen] does), this screen makes that surface the
 * backing store of a `VirtualDisplay`, shows a `Presentation` on it, and puts a
 * `WebView` inside the presentation. Everything the page draws lands on the head unit.
 *
 * Input: a navigation surface gives no touch input, but `SurfaceCallback` delivers
 * `onClick`/`onScroll` for taps and swipes the host forwards, so those are replayed
 * into the WebView as synthetic `MotionEvent`s. That is what makes the page usable in
 * the car rather than a picture of a page.
 *
 * Two things that bit earlier attempts (see [CarVideoSurface]): building displays and
 * views *inside* `onSurfaceAvailable` blocks the host's binder callback and gets the
 * surface reclaimed, so all of that is posted to the main thread, and the surface is
 * never released here (the host owns it).
 */
class CarWebScreen(
    private val ctx: CarContext,
    private val startUrl: String,
) : Screen(ctx) {

    /**
     * Main thread, always. A WebView MUST be built on the UI thread: creating it on a
     * HandlerThread trips a chromium CHECK and the process dies with
     * "Fatal signal 5 (SIGTRAP) … libwebviewchromium.so" the moment the screen opens.
     *
     * The ANR risk that pushed an earlier version onto a worker thread is handled the
     * other way round: chromium is pre-warmed on main before the car surface arrives
     * (see [prewarm]), and the display/Presentation/WebView work is posted a beat after
     * the host's surface callback returns instead of inside it.
     */
    private val handler = Handler(Looper.getMainLooper())

    private var display: VirtualDisplay? = null
    private var presentation: android.app.Presentation? = null
    private var web: WebView? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var viewW = 0
    private var viewH = 0

    /** Most recent page to load — survives the surface being reclaimed/reattached
     * when a non-navigation template (the search screen) covers this one. */
    private var lastUrl: String = startUrl

    /** Set when a search runs: the web screen's back target, so back returns to the
     * search results (not out of YouTube) after a result plays. Consumed on use — the
     * following back leaves. Kept separate from [lastUrl] because the playing video
     * does not move the WebView's own URL/history in a way `canGoBack()` reflects. */
    private var searchBackTarget: String? = null

    /** Media audio focus so the head unit routes the WebView's audio to the car.
     * Without it AA treats this nav-template screen as silent and mutes the page's
     * audio (plays on the DHU, where there is no focus arbitration, never in the car
     * unless some OTHER media app like CarStream grabbed focus first). Note the
     * Android Auto focus mediator is a media-session system, so if a raw focus grant
     * still doesn't route after a real-unit test, the escalation is a media session
     * (see PlaybackService) owned while this screen is up. */
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    /** No-op listener: we only need to hold the grant, not react to its loss. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    /**
     * ACTIVE media session held while this screen is up. The raw focus grant above
     * is not enough on Android Auto: the head unit routes car audio off the media
     * session (MediaSessionManager.getActiveSessions()), and a nav-template screen
     * with no session is treated as silent — the web page's audio only reaches the
     * car after some other media app grabs focus. Holding a session reporting
     * STATE_PLAYING makes MYTV itself the active media source.
     */
    private var audioSession: MediaSessionCompat? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val surface = container.surface ?: return
            val w = container.width
            val h = container.height
            val dpi = container.dpi
            Log.i(TAG, "web surface ${w}x$h @${dpi}dpi")
            // Post *and* delay: doing this inside onSurfaceAvailable is what the host
            // reports as ANR API: ON_SURFACE_AVAILABLE.
            handler.postDelayed({ attach(surface, w, h, dpi) }, 250L)
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            Log.i(TAG, "web surface destroyed")
            handler.post { detach() }
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            Log.d(TAG, "visible $visibleArea")
        }

        override fun onClick(x: Float, y: Float) { tap(x, y) }

        override fun onScroll(dx: Float, dy: Float) { scroll(dx, dy) }

        override fun onFling(dx: Float, dy: Float) { scroll(-dx, -dy) }
    }

    init {
        prewarm()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                runCatching { ctx.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback) }
                    .onFailure { Log.w(TAG, "surface callback rejected", it) }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                runCatching { ctx.getCarService(AppManager::class.java).setSurfaceCallback(null) }
                detach()
            }
        })
    }

    /**
     * Build and throw away one WebView on the UI thread so chromium's first-load cost
     * (provider init, ~1-2 s on this device) is paid before the car surface exists.
     * Without it the first real WebView blocks main while the host is waiting and the
     * car shows "MYTV isn't responding".
     */
    private fun prewarm() {
        handler.post {
            runCatching { WebView(ctx.applicationContext) }.getOrNull()?.let {
                it.loadUrl("about:blank")
                it.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attach(surface: Surface, width: Int, height: Int, dpi: Int) {
        detach()
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "ignoring empty surface ${width}x$height")
            return
        }
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return
        display = dm.createVirtualDisplay(
            "mytv-web",
            width,
            height,
            if (dpi > 0) dpi else 160,
            surface,
            // OWN_CONTENT_ONLY is mandatory: without it the framework treats a
            // PUBLIC display as screen mirroring and throws SecurityException
            // ("Requires ADD_MIRROR_DISPLAY … or an appropriate MediaProjection token").
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val vd = display ?: return

        val presentation = android.app.Presentation(ctx, vd.display)
        val root = FrameLayout(presentation.context)

        val webView = WebView(presentation.context)
        webView.setBackgroundColor(0xFF000000.toInt())
        webView.also { wv -> wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.settings.loadWithOverviewMode = true
            wv.settings.useWideViewPort = true
            wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
            wv.settings.userAgentString = UA
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean = false

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? =
                    if (com.rogueplayer.data.AdBlock.shouldBlock(request.url.toString())) {
                        com.rogueplayer.data.AdBlock.emptyResponse()
                    } else {
                        super.shouldInterceptRequest(view, request)
                    }

                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(com.rogueplayer.data.AdBlock.JS, null)
                    view.evaluateJavascript(SEARCH_HOOK_JS, null)
                    // Diagnostic: force the page into fullscreen so the custom-view path
                    // (YouTube's fullscreen button) can be exercised without a click.
                    if (java.io.File(ctx.filesDir, "car_web_fullscreen").exists()) {
                        view.postDelayed({
                            view.evaluateJavascript(
                                "(function(){var e=document.documentElement;" +
                                    "if(e&&e.requestFullscreen)e.requestFullscreen();})()",
                                null,
                            )
                        }, 6000L)
                    }
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                /**
                 * Fullscreen video. YouTube (and any page using the HTML5 fullscreen
                 * API) hands the playing view to the *host*; if the callback is not
                 * stored and invoked on hide, chromium keeps waiting for the host and the
                 * page wedges — and destroying a WebView that still owns a custom view
                 * traps the process. So: one view at a time, hide the page behind it, and
                 * always complete the callback.
                 */
                override fun onShowCustomView(view: View, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    // Ignore anything already attached — adding the same view twice
                    // throws IllegalStateException ("child already has a parent").
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.setBackgroundColor(0xFF000000.toInt())
                    customView = view
                    customCallback = callback
                    webView.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT))
                }

                override fun onHideCustomView() = hideCustom()
            }
        }
        // Bridge the on-page search box back to MYTV's URL-load path (see SearchBridge).
        webView.addJavascriptInterface(SearchBridge(), "MYTV")
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        // No on-screen transport: the user asked for it stripped ("strip the bar").
        // AA's own back still pops this screen, and the page keeps its own YouTube UI.

        presentation.setContentView(root)
        runCatching { presentation.show() }
            .onFailure { Log.w(TAG, "presentation refused to show", it) }

        webView.loadUrl(lastUrl)
        requestMediaFocus()
        ensureAudioSession()

        this.presentation = presentation
        this.web = webView
        viewW = width
        viewH = height
    }

    /** Tear the fullscreen video view down and tell chromium it can resume the page. */
    private fun hideCustom() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web?.visibility = View.VISIBLE
    }

    private fun detach() {
        // Order matters: a WebView destroyed while it still owns a custom view traps.
        hideCustom()
        web?.let {
            runCatching {
                it.stopLoading()
                it.loadUrl("about:blank")
                it.destroy()
            }
        }
        web = null
        abandonMediaFocus()
        releaseAudioSession()
        searchBackTarget = null
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        display?.let { runCatching { it.release() } }
        display = null
    }

    /** Replay a car tap as a WebView touch, scaled into the view's own coordinates. */
    private fun tap(x: Float, y: Float) { handler.post {
        // The back chip is ours, not the page's: hit-test it first, in surface pixels.
        val w = web ?: return@post
        val px = if (viewW > 0) x * w.width / viewW else x
        val py = if (viewH > 0) y * w.height / viewH else y
        val down = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, px, py, 0)
        w.dispatchTouchEvent(d)
        d.recycle()
        val up = SystemClock.uptimeMillis()
        val u = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, px, py, 0)
        w.dispatchTouchEvent(u)
        u.recycle()
    } }

    /** Car scroll deltas (positive = finger moved down) become a short drag. */
    private fun scroll(dx: Float, dy: Float) { handler.post {
        val w = web ?: return@post
        val x = w.width / 2f
        val y = w.height / 2f
        val down = SystemClock.uptimeMillis()
        val steps = 4
        val d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        w.dispatchTouchEvent(d)
        d.recycle()
        var last = down
        for (i in 1..steps) {
            val t = down + i * 16L
            val e = MotionEvent.obtain(down, t, MotionEvent.ACTION_MOVE,
                x, y - dy * i / steps, 0)
            w.dispatchTouchEvent(e)
            e.recycle()
            last = t
        }
        val u = MotionEvent.obtain(down, last + 16, MotionEvent.ACTION_UP, x, y - dy, 0)
        w.dispatchTouchEvent(u)
        u.recycle()
    } }

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setNavigationInfo(routing())
        .setActionStrip(headerStrip())
        .setMapActionStrip(mapStrip())
        .build()

    /** Same constraint as [VideoScreen]: the surface dies without live navigation info. */
    private fun routing(): RoutingInfo = RoutingInfo.Builder()
        .setLoading(false)
        .setCurrentStep(
            Step.Builder("YouTube").setRoad("YouTube").build(),
            Distance.create(0.0, Distance.UNIT_METERS),
        )
        .build()

    private fun headerStrip(): ActionStrip =
        ActionStrip.Builder().addAction(Action.APP_ICON).build()

    private fun mapStrip(): ActionStrip =
        ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(searchIcon())
                    // A navigation surface has no keyboard, so this is how you search:
                    // it opens a SearchTemplate (dictation/typing on the unit) and loads
                    // the finished query into the page.
                    .setOnClickListener {
                        screenManager.push(SearchScreen(ctx) { q -> runSearch(q) })
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(backIcon())
                    // Back means "previous page" while there is history, and only leaves
                    // the screen at the first page. (AA's own back cannot be intercepted —
                    // car-app 1.7 has no Screen.onBackPressed — so this chevron is where
                    // the page-level back lives.)
                    .setOnClickListener { goBack() }
                    .build()
            )
            .build()

    /**
     * Back, page-first: leave a fullscreen video, then walk the WebView history, and
     * only pop the car screen when the page has nowhere left to go.
     */
    private fun goBack() {
        if (customView != null) {
            web?.evaluateJavascript(
                "document.exitFullscreen && document.exitFullscreen();", null)
            hideCustom()
            return
        }
        val w = web
        if (w != null && w.canGoBack()) {
            Log.i(TAG, "back -> page history")
            w.goBack()
            return
        }
        // No retained history (YouTube's in-app viewer and our surface re-attaches drop
        // it). If a search set a target and we're no longer on it, return there without
        // consuming the target, so every search -> result -> back cycle works the same.
        val target = searchBackTarget
        if (w != null && target != null && !target.equals(w.url, ignoreCase = true)) {
            Log.i(TAG, "back -> search results $target")
            w.loadUrl(target)
            return
        }
        Log.i(TAG, "back -> leaving the web screen")
        screenManager.pop()
    }

    /** Back chevron drawn into a 64x64 bitmap — the map strip rejects custom titles. */
    private fun backIcon(): androidx.car.app.model.CarIcon {
        val size = 64
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = size * 0.10f
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val w = size.toFloat()
        val h = size.toFloat()
        canvas.drawLine(w * 0.58f, h * 0.20f, w * 0.34f, h * 0.50f, paint)
        canvas.drawLine(w * 0.34f, h * 0.50f, w * 0.58f, h * 0.80f, paint)
        return androidx.car.app.model.CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)).build()
    }

    /** Magnifier glyph for the search action (icon-only; the map strip takes no titles). */
    private fun searchIcon(): androidx.car.app.model.CarIcon {
        val size = 64
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = size * 0.09f
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val s = size.toFloat()
        canvas.drawCircle(s * 0.42f, s * 0.42f, s * 0.20f, paint)
        canvas.drawLine(s * 0.56f, s * 0.56f, s * 0.78f, s * 0.78f, paint)
        return androidx.car.app.model.CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)).build()
    }

    /**
     * Load a YouTube search from a query typed/dicted through [SearchScreen]. The
     * page can be alive (cover screen gone, re-attached) or mid-load, so update the
     * remembered URL either way and let attach() pick it up.
     */
    fun runSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val url = "https://m.youtube.com/results?search_query=" + Uri.encode(q)
        Log.i(TAG, "search -> $url")
        lastUrl = url
        searchBackTarget = url
        web?.loadUrl(url)
    }

    /** JS bridge (window.MYTV): the on-page YouTube search box hands its query back to
     *  MYTV instead of letting the mobile SPA hydrate results client-side — which renders
     *  a blank results grid inside a WebView. Mirrors [runSearch] so results load via the
     *  reliable /results URL and back-corrects to the results page after a video plays. */
    private inner class SearchBridge {
        @android.webkit.JavascriptInterface
        fun routeSearch(query: String) {
            val q = query.trim()
            if (q.isEmpty()) return
            handler.post {
                val url = "https://m.youtube.com/results?search_query=" + Uri.encode(q)
                Log.i(TAG, "search(bridge) -> $url")
                lastUrl = url
                searchBackTarget = url
                web?.loadUrl(url)
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * Grab media audio focus with STREAM_MUSIC (media category, gain). Android
     * mutes an app's audio track when it plays without holding focus — which is the
     * whole DHU-vs-car difference — so this must be requested before the page starts.
     * Best-effort: failure just means the head unit stays silent, same as today.
     */
    private fun requestMediaFocus() {
        if (audioFocusRequest != null) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        audioManager = am
        audioFocusRequest = request
        am.requestAudioFocus(request)
    }

    /** Release the focus grant so other apps aren't starved once this screen is gone. */
    private fun abandonMediaFocus() {
        audioFocusRequest?.let { req ->
            runCatching { audioManager?.abandonAudioFocusRequest(req) }
        }
        audioFocusRequest = null
        audioManager = null
    }

    /**
     * Register an ACTIVE media session reporting STATE_PLAYING. Android Auto's focus
     * manager keys car audio routing off this (see the field docs for why the raw
     * focus grant alone is not enough). Best-effort: failure leaves things silent,
     * same as before.
     */
    private fun ensureAudioSession() {
        if (audioSession != null) return
        audioSession = runCatching {
            MediaSessionCompat(ctx, "mytv-web").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE
                        )
                        .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                        .build()
                )
                setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_TITLE,
                            "YouTube in the car"
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE,
                            "YouTube in the car"
                        )
                        .build()
                )
                isActive = true
            }
        }.getOrNull()
    }

    /** Drop the session when the screen is torn down so it never claims audio after. */
    private fun releaseAudioSession() {
        audioSession?.let { s ->
            runCatching { s.isActive = false }
            runCatching { s.release() }
        }
        audioSession = null
    }

    private companion object {
        const val TAG = "CarWebScreen"

        /** Injected on every page: reroutes the on-page YouTube search box through MYTV's
         *  URL-load path (window.MYTV.routeSearch) rather than the mobile SPA's client-side
         *  results render, which shows a blank results grid inside a WebView. Fires on
         *  Enter/form-submit and re-hooks after the SPA re-renders its search input. */
        val SEARCH_HOOK_JS: String = """
            (function(){
              try {
                function hook(){
                  var inp = document.querySelector(
                    'input#search, input[name="search_query"], input[aria-label*="Search" i]');
                  if (!inp || inp.__mytv) return;
                  inp.__mytv = true;
                  var go = function(e){
                    var q = (inp.value || '').trim();
                    if (!q) return;
                    e.preventDefault();
                    e.stopPropagation();
                    try { window.MYTV && MYTV.routeSearch(q); }
                    catch (x) { location.href = 'https://m.youtube.com/results?search_query=' +
                      encodeURIComponent(q); }
                  };
                  inp.addEventListener('keydown', function(e){
                    if (e.key === 'Enter' || e.keyCode === 13) go(e);
                  });
                  var f = inp.closest && inp.closest('form');
                  if (f) f.addEventListener('submit', go);
                }
                hook();
                setInterval(hook, 1200);
              } catch (e) {}
            })();
        """.trimIndent()
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
