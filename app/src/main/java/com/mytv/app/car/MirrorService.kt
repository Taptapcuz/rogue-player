package com.mytv.app.car

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

private const val TAG = "MirrorService"

/**
 * Companion service for the projection host. Its presence gives Android Auto a
 * long-running component associated with the car task; the actual content
 * rendering happens in [ProjectionActivity] off the shared ExoPlayer.
 */
class MirrorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Log.d(TAG, "MirrorService destroyed")
        super.onDestroy()
    }
}
