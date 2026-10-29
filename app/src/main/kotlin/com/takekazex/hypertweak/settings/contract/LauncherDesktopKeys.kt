package com.takekazex.hypertweak.settings.contract

internal object LauncherDesktopKeys {
    const val KEY_ICON_SIZE_PERCENT = "home_icon_size_percent"
    const val FOLLOW_HOST = -1
    fun validPercent(value: Int): Boolean = value == FOLLOW_HOST || value in 50..150
}
