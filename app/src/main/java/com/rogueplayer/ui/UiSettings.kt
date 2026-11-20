package com.rogueplayer.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for Rogue Player's app-level settings (the full-screen
 * Settings screen in [SettingsActivity]). Keys are shared by name so any screen
 * can read them. Playback aspect intentionally lives under the existing
 * "mytv_player" pref (PlayerActivity already reads it as its default).
 */
object UiSettings {
    const val PREFS = "mytv_settings"

    // Display
    const val KEY_ACCENT = "accent"          // Int index into [ACCENT_PALETTES]
    const val KEY_BOOT = "boot_color"        // Int index into [ACCENT_PALETTES] (boot screen)
    const val KEY_BRIGHTNESS = "brightness"  // Int 0..100 (100 = full)
    const val KEY_LAUNCH_LIVE = "launch_live" // true = open Live tab instead of Home

    /** Playback engine: [ENGINE_EXO] (default) or [ENGINE_VLC]. Phone only for now. */
    const val KEY_ENGINE = "player_engine"
    const val ENGINE_EXO = "exo"
    const val ENGINE_VLC = "vlc"
    val ENGINE_NAMES = arrayOf("ExoPlayer", "VLC")

    // Playback
    const val KEY_FORCE_HLS = "force_hls"     // true = route .m3u8 through HlsMediaSource
    const val PLAYER_PREFS = "mytv_player"    // existing player prefs (aspect stored here)
    const val KEY_ASPECT = "aspect"

    /** Accent choices for "different them colors" (each is a 3-slot accent pair). */
    val ACCENT_NAMES = arrayOf(
        "Blue", "Cyan", "Violet", "Teal", "Amber", "Green",
        "Red", "Pink", "Magenta", "Lime", "Sky", "White",
    )
    val ACCENT_PALETTES = arrayOf(
        intArrayOf(0xFF3D7BFF.toInt(), 0xFF2BA8E6.toInt(), 0xFF7C4DFF.toInt()), // Blue
        intArrayOf(0xFF00B8E6.toInt(), 0xFF2BD9E6.toInt(), 0xFF0E8FE6.toInt()), // Cyan
        intArrayOf(0xFF8B4DFF.toInt(), 0xFFB04DFF.toInt(), 0xFF5C7CFF.toInt()), // Violet
        intArrayOf(0xFF1BA8A0.toInt(), 0xFF2FD6C8.toInt(), 0xFF1B7FA8.toInt()), // Teal
        intArrayOf(0xFFE6A400.toInt(), 0xFFFFB800.toInt(), 0xFFE67300.toInt()), // Amber
        intArrayOf(0xFF4FBF5F.toInt(), 0xFF7BD92B.toInt(), 0xFF2E8E57.toInt()), // Green
        intArrayOf(0xFFE8463D.toInt(), 0xFFFF5A4F.toInt(), 0xFFB32A2A.toInt()), // Red
        intArrayOf(0xFFFF4FA3.toInt(), 0xFFFF7BC4.toInt(), 0xFFE03A8C.toInt()), // Pink
        intArrayOf(0xFFC23DFF.toInt(), 0xFFD94FFF.toInt(), 0xFF8C3DFF.toInt()), // Magenta
        intArrayOf(0xFF7FD61F.toInt(), 0xFFB4E63B.toInt(), 0xFF5CA61F.toInt()), // Lime
        intArrayOf(0xFF29B8FF.toInt(), 0xFF66E0FF.toInt(), 0xFF2A7FFF.toInt()), // Sky
        intArrayOf(0xFFE8EAFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFB0B8E8.toInt()), // White
    )

    /** Boot-screen color (index into [ACCENT_PALETTES]); independent of the app accent. */
    fun bootIndex(context: Context): Int =
        prefs(context).getInt(KEY_BOOT, 0).coerceIn(0, ACCENT_PALETTES.size - 1)

    fun bootColor(context: Context): Int = ACCENT_PALETTES[bootIndex(context)][0]

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stored engine id; unknown values fall back to ExoPlayer. */
    fun engine(context: Context): String {
        val stored = prefs(context).getString(KEY_ENGINE, ENGINE_EXO)
        return if (stored == ENGINE_VLC) ENGINE_VLC else ENGINE_EXO
    }

    fun accentIndex(context: Context): Int =
        prefs(context).getInt(KEY_ACCENT, 0).coerceIn(0, ACCENT_PALETTES.size - 1)

    fun accent(context: Context, slot: Int): Int {
        val palette = ACCENT_PALETTES[accentIndex(context)]
        return when (slot) {
            2 -> palette[2]
            1 -> palette[1]
            else -> palette[0]
        }
    }

    fun brightness(context: Context): Int =
        prefs(context).getInt(KEY_BRIGHTNESS, 100).coerceIn(0, 100)

    fun forceHls(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_HLS, true)

    fun launchToLive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LAUNCH_LIVE, false)

    /** Dim the app window to the stored brightness (0..100 → 0..1). Real screen dim. */
    fun applyBrightness(activity: Activity) {
        val b = brightness(activity).coerceIn(0, 100) / 100f
        runCatching {
            val lp = activity.window.attributes
            lp.screenBrightness = b
            activity.window.attributes = lp
        }
    }
}
