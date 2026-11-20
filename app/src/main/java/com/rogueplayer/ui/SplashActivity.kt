package com.rogueplayer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Branded boot screen for Rogue Player (was MYTV): a ~3s transformer-style
 * sequence in which scattered shard particles fly in from the edges and lock
 * into the "ROGUE PLAYER" wordmark while a loading bar fills, then a fade to
 * the main UI. All animation lives in [SplashView] (pure Canvas); this activity
 * only hosts it and handles the handoff.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var splash: SplashView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        splash = SplashView(this)
        setContentView(splash, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        splash.start { transition() }
    }

    /** Fade the splash out, then hand off to the main UI. */
    private fun transition() {
        // One-shot "fresh boot" marker: MainActivity reads+clears it, so the Home
        // landing is the root only on a genuine launch (a killed task resurrected
        // from recents loses the marker and restores its last session instead).
        getSharedPreferences(MainActivity.SESSION_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MainActivity.FRESH_PENDING, true).commit()
        splash.animate().alpha(0f).setDuration(300).withEndAction {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_FRESH_LAUNCH, true))
            overridePendingTransition(0, 0)
            finish()
        }.start()
    }
}
