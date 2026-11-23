package com.rogueplayer.data

/**
 * The portal this build ships with, so a fresh install has content without
 * anyone typing a URL, username and password.
 *
 * The three strings are XOR-masked byte literals and are only assembled at
 * runtime, so `strings app.apk` / a resource dump will not surface them. That is
 * obfuscation, not security: the key ships in the same APK, so anyone who dumps
 * this class's memory or reverses the loop can recover them. Treat a build with
 * this file baked in as private — do not publish the APK.
 *
 * Behaviour: [com.rogueplayer.MyApplication] adds this source on start ONLY when
 * no playlist is stored yet, so an install that already has sources is never
 * touched and the user can still add or remove their own.
 */
object BakedSource {

    private val KEY = intArrayOf(90, -61, 39, -98, 65, -16, 107, 24)

    private val HOST_MASKED = intArrayOf(61, -85, 72, -19, 53, -126, 2, 124, 63, -19, 95, -25, 59, -54, 89, 44, 111, -11, 22)
    private val USER_MASKED = intArrayOf(56, -94, 69, -25, 35, -97, 18, 116, 53, -96)
    private val PASS_MASKED = intArrayOf(106, -10, 19, -87, 121, -61)

    /** True when this build carries a baked portal at all. */
    val enabled: Boolean get() = HOST_MASKED.isNotEmpty() && USER_MASKED.isNotEmpty()

    private fun unmask(masked: IntArray): String {
        val out = ByteArray(masked.size)
        for (i in masked.indices) {
            out[i] = (masked[i] xor KEY[i % KEY.size]).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    /** `http://host:port` — what the add-source form calls the server address. */
    fun base(): String = "http://" + unmask(HOST_MASKED)

    /** `http://host:port/player_api.php` — the Xtream API root. */
    fun url(): String = base() + "/player_api.php"

    fun user(): String = unmask(USER_MASKED)

    fun pass(): String = unmask(PASS_MASKED)

    /** Playlist name = the host, which is what the app derives for Xtream sources. */
    fun name(): String = unmask(HOST_MASKED)

    /** What the UI calls this source. Never the host: a baked build shows a brand
     *  line, so logging in does not display the panel address. */
    const val LABEL = "Rogue TV"

    fun displayName(stored: String): String =
        if (stored.equals(name(), ignoreCase = true)) LABEL else stored
}
