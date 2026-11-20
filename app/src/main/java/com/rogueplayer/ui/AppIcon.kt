package com.rogueplayer.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Keeps the launcher icon in the boot-screen colour.
 *
 * Android has no API to tint a launcher icon at runtime, so the manifest declares one
 * `<activity-alias>` per accent (`.Launcher.blue`, `.Launcher.cyan`, …), each pointing at
 * [SplashActivity] with its own baked `@mipmap/ic_launcher_<name>`. Exactly ONE may be
 * enabled or the launcher shows a duplicate icon, so this flips them together.
 *
 * [PackageManager.DONT_KILL_APP] matters: the default flag restarts the process and would
 * bounce the user out of Settings mid-change.
 */
object AppIcon {
    /** Must match the aliases in AndroidManifest.xml and UiSettings.ACCENT_PALETTES order. */
    private val NAMES = arrayOf(
        "blue", "cyan", "violet", "teal", "amber", "green",
        "red", "pink", "magenta", "lime", "sky", "white",
    )

    fun apply(context: Context, index: Int) {
        val pm = context.packageManager
        val wanted = NAMES[index.coerceIn(0, NAMES.size - 1)]
        for ((name, component) in aliases(context)) {
            val state = if (name == wanted) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (pm.getComponentEnabledSetting(component) != state) {
                runCatching {
                    pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }

    /** Sync the icon to the stored boot colour; a no-op when it already matches. */
    fun sync(context: Context) = apply(context, UiSettings.bootIndex(context))

    /**
     * Every `.Launcher.<name>` alias in the manifest, keyed by name.
     *
     * Read from PackageManager rather than built from packageName: the manifest namespace
     * (com.rogueplayer) differs from the applicationId (com.rogueplayer.basic in the Basic
     * edition), so a hand-built ComponentName matches nothing and the icon never changes.
     */
    private fun aliases(context: Context): Map<String, ComponentName> {
        val pm = context.packageManager
        val info = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_DISABLED_COMPONENTS
        )
        val out = LinkedHashMap<String, ComponentName>()
        info.activities?.forEach { activity ->
            val full = activity.name
            val marker = ".Launcher."
            val at = full.lastIndexOf(marker)
            if (at >= 0) out[full.substring(at + marker.length)] = ComponentName(context.packageName, full)
        }
        return out
    }
}
