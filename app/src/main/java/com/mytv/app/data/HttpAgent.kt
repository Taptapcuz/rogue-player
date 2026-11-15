package com.mytv.app.data

/**
 * One User-Agent for every provider request.
 *
 * Xtream panels (and the Cloudflare/Nginx WAFs in front of them) routinely drop
 * connections from client UAs — `okhttp/4.x` for the API and `ExoPlayerLib/1.x`
 * for streams — while serving the same account happily to a browser. That looks
 * exactly like "login failed / can't sign in" in the app while the provider's web
 * player works, so everything we send pretends to be a phone browser.
 */
object HttpAgent {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * Headers Chrome sends on a top-level navigation. Some WAFs reset a request that
     * carries only a User-Agent, so these ride along on every API call too.
     */
    val HEADERS: List<Pair<String, String>> = listOf(
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
        "sec-ch-ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "document",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "none",
        "sec-fetch-user" to "?1",
    )

    /** As a map, for Media3's `setDefaultRequestProperties`. */
    val STREAM_HEADERS: Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
    )
}
