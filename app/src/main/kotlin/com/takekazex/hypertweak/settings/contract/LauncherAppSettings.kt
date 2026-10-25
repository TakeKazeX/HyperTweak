package com.takekazex.hypertweak.settings.contract

internal interface LauncherAppSettings {
    fun openedFolderColumns(): Int
    fun allowAndroidWidgetsToAssistant(): Boolean
    fun androidWidgetStack(): Boolean
    fun folderStackPreview(): Boolean
    fun hideRecentsClearButton(): Boolean
    fun recentsCardHaptic(): Boolean
    fun xiaoaiGestureOptions(): Boolean
}
