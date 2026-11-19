package com.rogueplayer.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * MYTV's CarAppService. Registered under CATEGORY_PROJECTION + NAVIGATION so
 * Android Auto grants an unrestricted projection path. The service hands out a
 * [Session] that surfaces a playback/browse template, and any channel the user
 * picks is rendered by [ProjectionActivity] fullscreen on the car display.
 */
class RogueCarService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = ProjectionSession()

    /** Start the fullscreen video projection activity on the car / same task. */
    fun startProjection(context: androidx.car.app.CarContext, channelName: String) {
        val intent = Intent(context, ProjectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("title", channelName)
        context.startActivity(intent)
    }
}
