package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.SettingsDiagnostics

/** Launcher desktop grid, icon size, opened-folder columns and the retired Dock background. */
internal object LauncherDesktopPrefs {
    fun openedFolderColumns(): Int = Preferences.getInt(
        PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS,
        PlatformSettingsKeys.DEFAULT_OPENED_FOLDER_COLUMNS
    ).coerceIn(PlatformSettingsKeys.MIN_OPENED_FOLDER_COLUMNS, PlatformSettingsKeys.MAX_OPENED_FOLDER_COLUMNS)

    fun desktopGrid(): Int = Preferences.getInt(LauncherDesktopKeys.KEY_GRID, 0).takeIf(LauncherDesktopKeys::validGrid) ?: 0
    fun commitDesktopGrid(value: Int): Boolean {
        require(LauncherDesktopKeys.validGrid(value))
        return runCatching {
            Preferences.patchSettings(mapOf(LauncherDesktopKeys.KEY_GRID to value), emptySet())
            true
        }.onFailure { SettingsDiagnostics.w("DesktopGrid", "settings commit failed", it) }.getOrDefault(false)
    }

    fun desktopIconPercent(): Int = LauncherDesktopKeys.storedPercent(Preferences.getInt(LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT, -1)) ?: -1
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
}
