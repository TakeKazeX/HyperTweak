package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/** Launcher desktop grid, icon size and opened-folder columns. Writes live in `app/FeatureSettingsWriters`. */
internal object LauncherDesktopPrefs {
    fun openedFolderColumns(): Int = Preferences.getInt(
        PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS,
        PlatformSettingsKeys.DEFAULT_OPENED_FOLDER_COLUMNS
    ).coerceIn(PlatformSettingsKeys.MIN_OPENED_FOLDER_COLUMNS, PlatformSettingsKeys.MAX_OPENED_FOLDER_COLUMNS)

    fun desktopGrid(): Int = Preferences.getInt(LauncherDesktopKeys.KEY_GRID, 0).takeIf(LauncherDesktopKeys::validGrid) ?: 0

    fun desktopIconPercent(): Int = LauncherDesktopKeys.storedPercent(Preferences.getInt(LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT, -1)) ?: -1
}
