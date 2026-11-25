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
        installCrashLog()
        seedBakedSource()
    }

    /**
     * Keep the last crash on disk. There is no crash service behind this app, so the only way a
     * fault reaches anyone is a customer reading it out to them; a stack trace they can attach to
     * the bug report beats "it just closed". Writes files/last-crash.txt, then hands the throwable
     * to the previous handler so the normal crash dialog still appears.
     */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last-crash.txt").writeText(
                    System.currentTimeMillis().toString() + "\n" + Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Add the baked portal (see [BakedSource]) to a fresh install so it has content without
     * typing anything.
     *
     * Runs at most ONCE per install: a flag is set the first time it looks at the source
     * list, whether or not it added anything. That is what keeps a deleted source deleted —
     * seeding must never resurrect a playlist the user removed. Clearing app data resets it.
     *
     * Only builds produced with a local `baked.properties` have a portal at all.
     */
    private fun seedBakedSource() {
        // No baked login (a customer build): there is nothing to seed, and nothing of the
        // owner's should ever be written into somebody else's app.
        if (!BakedSource.autoSeed) return
        val seedPrefs = getSharedPreferences("mytv_seed", MODE_PRIVATE)
        if (seedPrefs.getBoolean(SEED_DONE, false)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repository.loadState.first { it }
            Log.i("RogueSeed", "baked portal available: " + BakedSource.name() +
                " (stored sources: " + repository.playlists.value.size + ")")
            if (repository.playlists.value.isNotEmpty()) {
                seedPrefs.edit().putBoolean(SEED_DONE, true).apply()
                return@launch
            }
            runCatching {
                PlaylistParser.load(
                    PlaylistKind.XTREAM,
                    BakedSource.url(),
                    BakedSource.user(),
                    BakedSource.pass(),
                )
            }.onSuccess { fresh ->
                repository.add(fresh.copy(name = BakedSource.name()))
                seedPrefs.edit().putBoolean(SEED_DONE, true).apply()
                Log.i("RogueSeed", "baked portal added: " + BakedSource.name())
            }.onFailure {
                // Not marked done: a network blip on first launch should be retried next time.
                Log.w("RogueSeed", "baked portal failed to load", it)
            }
        }
    }

    private companion object {
        /** Set once seeding has been considered for this install; never cleared by the app. */
        const val SEED_DONE = "baked_seeded"
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
