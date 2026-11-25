package com.rogueplayer.data

import com.rogueplayer.BuildConfig

/**
 * The portal this *build* ships with, so a fresh install has content without anyone typing
 * a URL, username and password.
 *
 * The three values are **not in the source tree**: `app/build.gradle.kts` reads them from
 * `baked.properties` at the repo root, which is gitignored. No file = no baked portal, and
 * a build made from a clean checkout simply starts empty with the add-playlist form — which
 * is what any published APK must do.
 *
 * That is a distribution rule, not security: anything the app can read at runtime can be
 * recovered from the APK. Treat a build with this baked in as a private build — do not hand
 * it out and do not publish it.
 *
 * Behaviour: [com.rogueplayer.MyApplication] adds this source on start ONLY when no playlist
 * is stored yet, so an install that already has sources is never touched and the user can
 * still add or remove their own.
 */
object BakedSource {

    private val HOST = BuildConfig.BAKED_HOST.trim()
    private val USER = BuildConfig.BAKED_USER.trim()
    private val PASS = BuildConfig.BAKED_PASS.trim()

    /**
     * True when this build knows the panel's address — enough to offer the "Rogue TV" tab, so
     * a customer only types a username and password.
     */
    val enabled: Boolean get() = HOST.isNotBlank()

    /**
     * True only when a full login is baked in as well (`-PwithAccount`). Gates the automatic
     * seeding: a customer build knows where the panel is but must never create a source with
     * somebody else's account.
     */
    val autoSeed: Boolean get() = HOST.isNotBlank() && USER.isNotBlank() && PASS.isNotBlank()

    private fun bare(): String =
        HOST.removePrefix("https://").removePrefix("http://").trimEnd('/')

    /** `http://host:port` — what the add-source form calls the server address. */
    fun base(): String = "http://" + bare()

    /** `http://host:port/player_api.php` — the Xtream API root. */
    fun url(): String = base() + "/player_api.php"

    fun user(): String = USER

    fun pass(): String = PASS

    /** Playlist name = the host, which is what the app derives for Xtream sources. */
    fun name(): String = bare()

    /** What the UI calls this source when the stored name is the host. */
    const val LABEL = "Rogue TV"

    fun displayName(stored: String): String =
        if (stored.equals(name(), ignoreCase = true)) LABEL else stored
}
