package com.mytv.app

import android.app.Application
import android.util.Log
import com.mytv.app.data.PlaylistRepository
import java.io.File
import java.util.Date

class MyApplication : Application() {
    lateinit var repository: PlaylistRepository
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        repository = PlaylistRepository(this)
    }

    /**
     * Record the last fatal exception to `files/last-crash.txt` (then defer to the
     * platform handler). Field crashes are otherwise lost on a release install —
     * pull it with: adb shell run-as com.mytv.app cat files/last-crash.txt
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
