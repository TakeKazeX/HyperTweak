package com.takekazex.hypertweak.settings.contract

internal interface LauncherAppSettings {
    fun desktopGrid(): Int = 0
    fun commitDesktopGrid(value: Int): Boolean = false
    fun desktopIconPercent(): Int
    fun commitDesktopIconPercent(value: Int): Boolean
    fun openedFolderColumns(): Int
    fun allowAndroidWidgetsToAssistant(): Boolean
    fun androidWidgetStack(): Boolean
    fun folderStackPreview(): Boolean
    fun hideRecentsClearButton(): Boolean
    fun recentsCardHaptic(): Boolean
    fun xiaoaiGestureOptions(): Boolean
}
