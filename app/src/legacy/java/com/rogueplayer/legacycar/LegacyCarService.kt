package com.rogueplayer.legacycar

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/**
 * Legacy-edition car service — the CarStream/Fermata shape.
 *
 * Registered under CATEGORY_PROJECTION (see src/legacy/AndroidManifest.xml), NOT
 * the modern Car App Library's NAVIGATION category. gearhead binds this service
 * and the SDK hosts [LegacyCarActivity] itself — no activity manifest entry, no
 * templates, no surface callbacks. Because AA sees a projection/media app:
 *  - AA boots to the launcher/last nav app (Maps), not into Rogue;
 *  - finishing the car activity is a REAL exit back to the launcher.
 */
class LegacyCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = LegacyCarActivity::class.java
}
