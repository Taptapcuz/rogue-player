package com.rogueplayer.ui

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
        splash.animate().alpha(0f).setDuration(300).withEndAction {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }.start()
    }
}
