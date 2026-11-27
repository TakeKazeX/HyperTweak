package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.CameraLegendaryMomentMode
import com.takekazex.hypertweak.settings.contract.CameraSettingsKeys

/** Leica/Legendary Moment selector, with migration of the legacy boolean. */
internal object CameraMomentPrefs {
    /**
     * Current Leica/Legendary Moment selector. Leica Moment opens its entry on the native
     * config; Legendary Moment uses Madrid only when the camera package contains that config.
     * A missing new key migrates the old Boolean (true → Leica Moment).
     */
    fun cameraLegendaryMomentMode(): String {
        val stored = if (Preferences.contains(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE)) {
            Preferences.getString(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE, "")
        } else {
            null
        }
        val legacy = if (stored == null && Preferences.contains(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT)) {
            Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT, false)
        } else {
            null
        }
        return CameraLegendaryMomentMode.resolve(stored, legacy)
    }
}
