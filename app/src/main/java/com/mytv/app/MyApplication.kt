package com.mytv.app

import android.app.Application
import com.mytv.app.data.PlaylistRepository

class MyApplication : Application() {
    lateinit var repository: PlaylistRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = PlaylistRepository(this)
    }
}
