package com.rogueplayer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * A browser inside MYTV.
 *
 * The point is YouTube: YouTube has no playable SDK for native apps, so the only way to
 * show it in-app without extracting streams is to render the mobile site in a WebView.
 * The same screen browses anything else (panel web UIs, a show's wiki page, …).
 *
 * Deliberate details:
 *  - `setMediaPlaybackRequiresUserGesture(false)` — without it a page video refuses to
 *    start and YouTube just sits on a poster frame.
 *  - `WebChromeClient.onShowCustomView` — YouTube's fullscreen button hands the video to
 *    the *host* via a custom view; ignoring it leaves a letterboxed mini player forever.
 *  - A real `WebViewClient` so links stay in-app instead of bouncing to Chrome.
 *  - Desktop-ish UA is NOT used: request the mobile site, it is far lighter on a phone.
 *
 * What it cannot do: DRM services (Netflix) won't decode in a WebView, and none of this
 * can be drawn into the Android Auto video surface — the car keeps its own player.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var backBtn: TextView
    private lateinit var forwardBtn: TextView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: FrameLayout

    /** When true, non-URL text searches Google instead of YouTube (the web pill). */
    private var webSearch = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webSearch = intent?.getBooleanExtra(EXTRA_WEB_SEARCH, false) ?: false

        root = FrameLayout(this)
        root.setBackgroundColor(Palette.BG)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildToolbar())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENTS[0])
            backgroundTintList = android.content.res.ColorStateList.valueOf(Palette.LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        }
        column.addView(progress)

        web = WebView(this).apply {
            setBackgroundColor(Palette.BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = MOBILE_UA
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean = false          // keep everything in this WebView

                // Ad/tracker requests never leave the device.
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
                    urlBar.setText(view.url ?: "")
                    syncNav()
                    view.evaluateJavascript(com.rogueplayer.data.AdBlock.JS, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    val bar = this@BrowserActivity.progress
                    bar.progress = newProgress
                    bar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback?) {
                    // YouTube fullscreen: swap the webview for the host-supplied view.
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT))
                    column.visibility = View.GONE
                }

                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    column.visibility = View.VISIBLE
                }
            }
        }
        column.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        val start = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: if (webSearch) GOOGLE_HOME else HOME_URL
        web.loadUrl(start)
        urlBar.setText(start)

        // The web pill wants to type immediately: focus the field and raise the keyboard.
        if (webSearch) {
            urlBar.requestFocus()
            urlBar.post {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Palette.PANEL)
        }

        backBtn = icon("‹") { if (web.canGoBack()) web.goBack() }
        forwardBtn = icon("›") { if (web.canGoForward()) web.goForward() }
        bar.addView(backBtn)
        bar.addView(forwardBtn)
        bar.addView(icon("⟳") { web.reload() })

        urlBar = EditText(this).apply {
            setSingleLine()
            textSize = 13f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.DIM)
            hint = "Search or type a URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(Palette.CARD, 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnEditorActionListener { _, _, _ ->
                load(urlBar.text.toString())
                true
            }
        }
        bar.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(6); marginEnd = dp(6) })

        bar.addView(icon("⌂") { load(HOME_URL) })
        bar.addView(icon("✕") { finish() })
        return bar
    }

    /**
     * Anything typed that is not a URL is searched — on YouTube for the YouTube entry,
     * on Google when opened from the Home web pill (so it can search the whole web).
     */
    private fun load(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            webSearch -> "https://www.google.com/search?q=" + Uri.encode(text)
            text.contains(" ") || !text.contains(".") ->
                "https://m.youtube.com/results?search_query=" + Uri.encode(text)
            else -> "https://$text"
        }
        web.loadUrl(url)
        urlBar.clearFocus()
    }

    private fun syncNav() {
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f
        forwardBtn.alpha = if (web.canGoForward()) 1f else 0.35f
    }

    private fun icon(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Palette.MUTED)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }

    private fun rounded(fill: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Hardware back walks the history first; leaves the screen only at the start page. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustomView()
            web.canGoBack() -> web.goBack()
            else -> {
                super.onBackPressed()
                overridePendingTransition(0, 0)
            }
        }
    }

    /** Undo whatever fullscreen video swap the page asked for. */
    private fun hideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        (root.getChildAt(0) as? View)?.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        runCatching {
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_WEB_SEARCH = "webSearch"
        private const val HOME_URL = "https://m.youtube.com"
        private const val GOOGLE_HOME = "https://www.google.com"

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun intent(context: android.content.Context, url: String? = null): Intent =
            Intent(context, BrowserActivity::class.java).apply {
                url?.let { putExtra(EXTRA_URL, it) }
            }

        /** Opens the browser as a web search: Google start page + focused, search-on-Google. */
        fun webIntent(context: android.content.Context): Intent =
            Intent(context, BrowserActivity::class.java).apply {
                putExtra(EXTRA_WEB_SEARCH, true)
            }
    }
}
