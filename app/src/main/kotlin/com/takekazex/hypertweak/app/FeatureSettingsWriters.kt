package com.takekazex.hypertweak.app

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.preferences.PowerSavePrefs
import com.takekazex.hypertweak.settings.contract.BackGestureKeys
import com.takekazex.hypertweak.settings.contract.CameraSettingsKeys
import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.PowerSaveOverrideSelection
import com.takekazex.hypertweak.util.logging.SettingsDiagnostics

/**
 * Settings edits that are more than one `put`: migrations of legacy keys and selections published
 * as one acknowledged snapshot. They run only in the app process, behind [PreferencesAppSettings];
 * hooks read the same keys through the `<Feature>Prefs` readers in `hook/preferences`.
 */
internal object FeatureSettingsWriters {
    /** Persists the long-press power action and drops the superseded legacy boolean. */
    fun setPowerButtonAction(action: Int) {
        Preferences.commitRuntimeChanges(mapOf(PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION to action,
            PlatformSettingsKeys.KEY_POWER_BUTTON_CTS to false)) {
            putInt(PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION, action)
            remove(PlatformSettingsKeys.KEY_POWER_BUTTON_CTS)
        }
    }

    /** Persists the selected Leica/Legendary Moment name and drops the superseded Boolean. */
    fun setCameraLegendaryMomentMode(mode: String) {
        Preferences.commitRuntimeChanges(mapOf(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE to mode,
            CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT to false)) {
            putString(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE, mode)
            remove(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT)
        }
    }

    fun commitDesktopGrid(value: Int): Boolean {
        require(LauncherDesktopKeys.validGrid(value))
        return runCatching {
            Preferences.patchSettings(mapOf(LauncherDesktopKeys.KEY_GRID to value), emptySet())
            true
        }.onFailure { SettingsDiagnostics.w("DesktopGrid", "settings commit failed", it) }.getOrDefault(false)
    }

    fun commitDesktopIconPercent(value: Int): Boolean {
        require(LauncherDesktopKeys.validPercent(value))
        return runCatching {
            Preferences.patchSettings(mapOf(LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT to value), emptySet())
            true
        }.onFailure { SettingsDiagnostics.w("DesktopIconSize", "settings commit failed", it) }.getOrDefault(false)
    }

    /** The retired WMS Surface Dock style, read only to hand an enabled switch to the native glass. */
    private const val KEY_LEGACY_DOCK_BACKGROUND = "launcher_dock_background"

    /** Removes the retired Dock setting in one transaction, enabling the native glass if it was on. */
    fun migrateLegacyDockBackground(): Boolean {
        if (!Preferences.contains(KEY_LEGACY_DOCK_BACKGROUND)) return false
        // Encoded as "version|enabled|...": only the enabled field survives the retirement.
        val enabled = Preferences.getString(KEY_LEGACY_DOCK_BACKGROUND, "").split('|').getOrNull(1) == "1"
        return runCatching {
            Preferences.patchSettings(if (enabled) mapOf(PlatformSettingsKeys.KEY_NATIVE_PAD_DOCK to true) else emptyMap(),
                setOf(KEY_LEGACY_DOCK_BACKGROUND))
            SettingsDiagnostics.i("DockBackground", "legacy setting retired, native glass ${if (enabled) "enabled" else "unchanged"}")
            enabled
        }.onFailure { SettingsDiagnostics.w("DockBackground", "legacy setting retirement failed", it) }.getOrDefault(false)
    }

    /** Preserve the effective legacy selection when editing one independent power-save feature. */
    fun setPowerSaveFeature(key: String, enabled: Boolean) {
        require(key in PowerSavePrefs.featureKeys)
        val master = PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER
        val values = PowerSaveOverrideSelection.edit(
            Preferences.getBoolean(master, false),
            PowerSavePrefs.featureKeys.associateWith { Preferences.getBoolean(it, false) }, key, enabled,
        )
        PowerSavePrefs.publish(values + (master to true))
    }

    /** Publish a back-gesture dropdown selection as one acknowledged snapshot, without intermediate styles. */
    fun putBackGestureAppearance(values: Map<String, Boolean>) {
        val allowed = setOf(BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_INDICATOR, BackGestureKeys.KEY_BACK_GESTURE_INDICATOR_BLUR,
            BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_HAPTICS, BackGestureKeys.KEY_BACK_GESTURE_HAPTICS_DISABLED)
        require(values.isNotEmpty() && values.keys.all { it in allowed })
        val changes = values.toMap()
        Preferences.commitRuntimeChanges(changes) {
            changes.forEach { (key, value) -> putBoolean(key, value) }
        }
    }
}
