package com.takekazex.hypertweak.ui.app

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Activity-scoped app settings. Properties are snapshot state and write-through: pages read them
 * and assign them directly (`appSettings.x = value`), which persists the value and records restart
 * tracking. Page-local settings without an [AppSettingsState] property use [LocalAppSettingsStore].
 */
internal val LocalAppSettings = staticCompositionLocalOf<AppSettingsState> {
    error("LocalAppSettings is provided by AppContent")
}
