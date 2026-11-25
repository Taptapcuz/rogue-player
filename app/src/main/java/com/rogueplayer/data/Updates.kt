package com.rogueplayer.data

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Is there a newer build?" — asked of the project's release feed.
 *
 * The repo is private today, so an unauthenticated call answers 404; that is reported as
 * "couldn't check", never as "up to date". Once the repo is public the same call works with
 * no key, which is why there is nothing to configure or leak here.
 */
object Updates {

    private const val TAG = "RogueUpdates"
    private const val API = "https://api.github.com/repos/Taptapcuz/rogue-player/releases/latest"

    /** A published build, as the feed describes it. */
    data class Latest(
        val tag: String,
        val versionName: String,
        val code: Int,
        val pageUrl: String,
        val apkUrl: String?,
    )

    /** Blocking network call — run it off the main thread. Null means "no feed to read". */
    fun check(): Result<Latest?> = runCatching {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RoguePlayer")
        }
        try {
            if (conn.responseCode != 200) {
                Log.i(TAG, "release feed: HTTP " + conn.responseCode)
                return@runCatching null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val tag = o.optString("tag_name").trim()
            // versionCode is the commit count, so the tag carries it: v1.0.52 -> 52.
            val code = tag.split(".", "-", "_", "+").lastOrNull()?.toIntOrNull() ?: 0
            var apk: String? = null
            val assets = o.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apk = a.optString("browser_download_url")
                    break
                }
            }
            Latest(
                tag = tag,
                versionName = o.optString("name").ifBlank { tag },
                code = code,
                pageUrl = o.optString("html_url"),
                apkUrl = apk,
            )
        } finally {
            conn.disconnect()
        }
    }
}
