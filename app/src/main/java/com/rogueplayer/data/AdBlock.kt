package com.rogueplayer.data

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Ad blocking for the in-app browser and the car web screen.
 *
 * Two layers, because YouTube serves its ads two ways:
 *
 *  1. **Request blocking** — `shouldInterceptRequest` refuses the ad/tracking hosts
 *     (doubleclick, googlesyndication, the `/pagead/` and `/api/stats/ads` paths on
 *     youtube.com). This is what stops most pre-rolls being fetched at all, and it also
 *     stops the tracking pings that make a page feel slow.
 *  2. **Cosmetic/skip JS** — a small script injected after load hides the masthead ad
 *     slots and clicks "Skip Ad" as soon as it appears, for the ads that still slip
 *     through when YouTube stitches one into the same stream request.
 *
 * Honest limits: a WebView cannot strip an ad that is muxed into the video stream, so
 * the second layer only helps where YouTube renders a skippable element. Nothing here
 * touches OUR player — Xtream streams carry no ads to begin with.
 */
object AdBlock {

    /** Exact hosts and suffixes that only ever serve ads, stats or trackers. */
    private val BLOCKED_HOSTS = listOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "googletagservices.com",
        "googletagmanager.com",
        "google-analytics.com",
        "analytics.google.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "static.doubleclick.net",
        "youtube.com/api/stats/ads",
        "youtube.com/pagead/",
        "youtube.com/ptracking",
        "youtube.com/get_video_info?adformat",
        "googlevideo.com/videoplayback?adformat",
        "manifest.googlevideo.com",
    )

    /** Path fragments that mark an ad request regardless of host. */
    private val BLOCKED_PATHS = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/ptracking",
        "/get_midroll_info",
        "/api/stats/atr",
        "adformat=",
        "&oad=",
        "/vast.xml",
    )

    /** True when [url] should not be fetched. */
    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()
        val hit = BLOCKED_PATHS.any { lower.contains(it) } ||
            hostOf(lower)?.let { host -> BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") } } == true
        if (hit) android.util.Log.i("AdBlock", "blocked $url")
        return hit
    }

    /** Empty 204-style response handed back instead of the ad. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun hostOf(url: String): String? {
        val start = url.indexOf("://") + 3
        if (start <= 2) return null
        val rest = url.substring(start)
        val end = rest.indexOfAny(charArrayOf('/', '?', ':'))
        return if (end > 0) rest.substring(0, end) else rest
    }

    /**
     * Runs on every page load. Kept tiny and wrapped in try/catch: YouTube's DOM changes,
     * and a throwing script would sit in the console forever.
     */
    val JS: String = """
        (function(){
          try {
            var css = document.createElement('style');
            css.textContent = [
              '#masthead-ad','#player-ads','ytd-display-ad-renderer',
              'ytd-promoted-sparkles-web-renderer','ytd-in-feed-ad-layout-renderer',
              'ytd-ad-slot-renderer','.ytp-ad-overlay-container',
              '.ytp-ad-progress-list','ytmusic-mealbar-promo-renderer'
            ].join(',') + '{display:none !important;}';
            document.head.appendChild(css);
            var skip = function(){
              var b = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern');
              if (b) { b.click(); }
              var v = document.querySelector('video');
              var ad = document.querySelector('.ad-showing');
              if (ad && v && isFinite(v.duration) && v.duration > 0) { v.currentTime = v.duration; }
            };
            setInterval(skip, 700);
          } catch (e) {}
        })();
    """.trimIndent()
}
