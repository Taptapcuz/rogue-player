package com.rogueplayer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Full-screen Rogue Player settings, grouped into sections the way IMPlayer /
 * VLC-style players lay out: Playback, Display, Sources, App data, About.
 * Real controls write to [UiSettings] (or the existing player prefs). Rows that
 * are not yet wired are marked "(coming)" rather than pretending to work.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var column: LinearLayout
    private var brightLabel: TextView? = null
    private var brightVal = 100

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        UiSettings.applyBrightness(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(Palette.TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(0), dp(14), dp(0))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Settings"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = false }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        // Keep the header clear of the status bar (edge-to-edge).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, top, 0, 0)
            insets
        }

        render()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        column.removeAllViews()

        // ------------------------------- PLAYBACK -------------------------------
        section("Playback")

        val aspect = AspectMode.from(UiSettings.prefs(this)
            .getString(UiSettings.KEY_ASPECT, AspectMode.AUTO.name))
        row(
            title = "Default aspect",
            sub = aspect.label,
            icon = "▭",
            onClick = {
                val modes = AspectMode.entries
                val cur = AspectMode.from(UiSettings.prefs(this)
                    .getString(UiSettings.KEY_ASPECT, AspectMode.AUTO.name)).ordinal
                AlertDialog.Builder(this)
                    .setTitle("Default aspect")
                    .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), cur) { d, which ->
                        UiSettings.prefs(this).edit()
                            .putString(UiSettings.KEY_ASPECT, modes[which].name).apply()
                        d.dismiss()
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })

        val forceHls = UiSettings.forceHls(this)
        row(
            title = "Force HLS",
            sub = "Route .m3u8 streams through the HLS player (fixes streams that don't autodetect)",
            icon = "▶",
            onClick = { toggle(UiSettings.KEY_FORCE_HLS, !forceHls) },
            right = switch(forceHls, UiSettings.KEY_FORCE_HLS))

        // Player engine: ExoPlayer (default) or libVLC. ExoPlayer covers the mainstream
        // cases and is the only engine wired to the car; VLC is the fallback for containers
        // and codecs the provider serves that ExoPlayer rejects (MKV/HEVC, AC3/E-AC3, odd HLS).
        val engine = UiSettings.engine(this)
        row(
            title = "Player engine",
            sub = if (engine == UiSettings.ENGINE_VLC) "VLC · phone only (car uses ExoPlayer)"
                  else "ExoPlayer",
            icon = "◈",
            onClick = {
                val current = if (UiSettings.engine(this) == UiSettings.ENGINE_VLC) 1 else 0
                AlertDialog.Builder(this)
                    .setTitle("Player engine")
                    .setSingleChoiceItems(UiSettings.ENGINE_NAMES, current) { d, which ->
                        UiSettings.prefs(this).edit().putString(
                            UiSettings.KEY_ENGINE,
                            if (which == 1) UiSettings.ENGINE_VLC else UiSettings.ENGINE_EXO
                        ).apply()
                        d.dismiss()
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            right = accentSwatch(UiSettings.accentIndex(this)))

        // -------------------------------- DISPLAY -------------------------------
        section("Display")

        val accentName = UiSettings.ACCENT_NAMES[UiSettings.accentIndex(this)]
        row(
            title = "Accent color",
            sub = accentName,
            icon = "●",
            onClick = {
                val cur = UiSettings.accentIndex(this)
                AlertDialog.Builder(this)
                    .setTitle("Accent color")
                    .setSingleChoiceItems(UiSettings.ACCENT_NAMES, cur) { d, which ->
                        UiSettings.prefs(this@SettingsActivity).edit().putInt(UiSettings.KEY_ACCENT, which).apply()
                        // Swap the global accent so every screen themes immediately.
                        Palette.ACCENTS = UiSettings.ACCENT_PALETTES[which]
                        d.dismiss()
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            right = accentSwatch(UiSettings.accentIndex(this)))

        // Boot screen has its OWN color, independent of the app accent. Default = original blue.
        val bootIdx = UiSettings.bootIndex(this)
        row(
            title = "Boot screen color",
            // The boot screen is drawn from the colour read at launch, and the launcher icon
            // alias switches with it — neither shows until the app is restarted.
            sub = "${UiSettings.ACCENT_NAMES[bootIdx]} · changing this reboots the app",
            icon = "✦",
            onClick = {
                AlertDialog.Builder(this)
                    .setTitle("Boot screen color")
                    .setSingleChoiceItems(UiSettings.ACCENT_NAMES, UiSettings.bootIndex(this)) { d, which ->
                        UiSettings.prefs(this@SettingsActivity).edit().putInt(UiSettings.KEY_BOOT, which).apply()
                        // Re-skin the launcher icon to the new boot colour (alias swap).
                        AppIcon.apply(this@SettingsActivity, which)
                        d.dismiss()
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            right = accentSwatch(bootIdx))

        // Brightness: inline slider (dim overlay applied by MainActivity).
        brightVal = UiSettings.brightness(this)
        row(
            title = "Brightness",
            sub = "$brightVal%",
            icon = "◐",
            onClick = { /* the slider below is the control */ },
            right = brightnessSlider(brightVal),
            onSubReady = { brightLabel = it })

        val launchLive = UiSettings.launchToLive(this)
        row(
            title = "Start on Live TV",
            sub = "Open the Live tab instead of the Home landing on launch",
            icon = "⌂",
            onClick = { toggle(UiSettings.KEY_LAUNCH_LIVE, !launchLive) },
            right = switch(launchLive, UiSettings.KEY_LAUNCH_LIVE))

        // -------------------------------- SOURCES --------------------------------
        section("Sources")
        row(
            title = "Sources & Playlists",
            sub = "Add, update or delete Xtream / M3U sources",
            icon = "⤓",
            onClick = {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_OPEN_SOURCES, true))
                finish()
            })

        // -------------------------------- APP DATA -------------------------------
        section("App data")
        row(
            title = "Clear app data",
            sub = "Remove cached TV guide & poster images",
            icon = "✕",
            onClick = {
                AlertDialog.Builder(this)
                    .setTitle("Clear app data?")
                    .setMessage("This removes cached guides and poster images. Your playlists and favorites are kept.")
                    .setPositiveButton("Clear") { _, _ -> clearAppData() }
                    .setNegativeButton("Cancel", null)
                    .show()
            })

        // --------------------------------- ABOUT ---------------------------------
        section("About")
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        row("Version", ver, "ℹ")
    }

    // ------------------------------------------------------------------ helpers --

    private fun section(title: String) {
        column.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 11f
            letterSpacing = 0.16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(UiSettings.accent(this@SettingsActivity, 0))
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    @SuppressLint("SetTextI18n")
    private fun row(
        title: String,
        sub: String,
        icon: String,
        onClick: () -> Unit = {},
        right: View? = null,
        disabled: Boolean = false,
        onSubReady: ((TextView) -> Unit)? = null,
    ) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = rounded(Palette.CARD, 12)
            isClickable = !disabled
            isEnabled = !disabled
            if (!disabled) setOnClickListener { onClick() }
            alpha = if (disabled) 0.45f else 1f
        }
        r.addView(TextView(this).apply {
            text = icon
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (disabled) Palette.DIM else UiSettings.accent(this@SettingsActivity, 0))
            setPadding(0, 0, dp(12), 0)
        })
        r.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 14.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (disabled) Palette.DIM else Palette.TEXT)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = sub
                textSize = 11.5f
                setTextColor(Palette.MUTED)
                setPadding(0, dp(2), 0, 0)
                onSubReady?.invoke(this)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        if (right != null) r.addView(right)
        // chevron affordance for rows whose control is a next tap
        r.addView(TextView(this).apply {
            text = if (disabled) "" else "›"
            textSize = 18f
            setTextColor(Palette.DIM)
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(2), 0)
        })
        column.addView(r, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    private fun switch(on: Boolean, key: String): Switch =
        Switch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, checked ->
                UiSettings.prefs(this@SettingsActivity).edit().putBoolean(key, checked).apply()
            }
        }

    private fun accentSwatch(idx: Int): View {
        val c = UiSettings.ACCENT_PALETTES[idx][0]
        return View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
    }

    private fun brightnessSlider(initial: Int): SeekBar =
        SeekBar(this).apply {
            max = 100
            progress = initial
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(30))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        UiSettings.prefs(this@SettingsActivity).edit().putInt(UiSettings.KEY_BRIGHTNESS, p).apply()
                        brightVal = p
                        brightLabel?.text = "$p%"
                        UiSettings.applyBrightness(this@SettingsActivity)  // live, no re-enter needed
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

    private fun toggle(key: String, value: Boolean) {
        UiSettings.prefs(this).edit().putBoolean(key, value).apply()
        render()
    }

    private fun clearAppData() {
        runCatching { File(filesDir, "epg").deleteRecursively() }
        runCatching {
            val coil = applicationContext.getSharedPreferences("coil_preferences", 0)
            coil.edit().clear().apply()
        }
        android.widget.Toast.makeText(this, "App data cleared", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun rounded(fill: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
