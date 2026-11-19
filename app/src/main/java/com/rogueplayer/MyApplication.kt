package com.rogueplayer

import android.app.Application
import android.util.Log
import com.rogueplayer.data.PlaylistRepository
import com.rogueplayer.ui.Palette
import com.rogueplayer.ui.UiSettings
import java.io.File
import java.util.Date

class MyApplication : Application() {
    lateinit var repository: PlaylistRepository
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // Load the user's chosen accent into the global palette at start, so every
        // screen that reads Palette.ACCENTS the moment it builds picks it up.
        Palette.ACCENTS = UiSettings.ACCENT_PALETTES[UiSettings.accentIndex(this)]
        repository = PlaylistRepository(this)
    }

    /**
     * Record the last fatal exception to `files/last-crash.txt` (then defer to the
     * platform handler). Field crashes are otherwise lost on a release install —
     * pull it with: adb shell run-as com.rogueplayer cat files/last-crash.txt
     */
    private fun installCrashLogger() {
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last-crash.txt").writeText(
                    "time=${Date()}\nthread=${thread.name}\n" + Log.getStackTraceString(error)
                )
            }
            platform?.uncaughtException(thread, error)
        }
    }
}
