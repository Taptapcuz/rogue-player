package com.rogueplayer

import android.app.Application
import android.util.Log
import com.rogueplayer.data.BakedSource
import com.rogueplayer.data.PlaylistKind
import com.rogueplayer.data.PlaylistParser
import com.rogueplayer.data.PlaylistRepository
import com.rogueplayer.ui.AppIcon
import com.rogueplayer.ui.Palette
import com.rogueplayer.ui.UiSettings
import java.io.File
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MyApplication : Application() {
    lateinit var repository: PlaylistRepository
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // Load the user's chosen accent into the global palette at start, so every
        // screen that reads Palette.ACCENTS the moment it builds picks it up.
        Palette.ACCENTS = UiSettings.ACCENT_PALETTES[UiSettings.accentIndex(this)]
        // Keep the launcher icon in the stored boot-screen colour (alias swap; no-op when set).
        AppIcon.sync(this)
        repository = PlaylistRepository(this)
        seedBakedSource()
    }

    /**
     * Add the baked portal (see [BakedSource]) when the install has no playlist
     * yet, so nothing has to be typed on a fresh install. Never overwrites or
     * re-adds: an install with stored sources is left exactly as it is.
     */
    private fun seedBakedSource() {
        if (!BakedSource.enabled) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repository.loadState.first { it }
            // Decode check on every start: a masked literal assembled wrongly
            // shows up here as garbage, without touching the user's data.
            Log.i("RogueSeed", "baked portal available: " + BakedSource.name() +
                " (stored sources: " + repository.playlists.value.size + ")")
            if (repository.playlists.value.isNotEmpty()) return@launch
            runCatching {
                PlaylistParser.load(
                    PlaylistKind.XTREAM,
                    BakedSource.url(),
                    BakedSource.user(),
                    BakedSource.pass(),
                )
            }.onSuccess { fresh ->
                repository.add(fresh.copy(name = BakedSource.name()))
                Log.i("RogueSeed", "baked portal added: " + BakedSource.name())
            }.onFailure {
                Log.w("RogueSeed", "baked portal failed to load", it)
            }
        }
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
