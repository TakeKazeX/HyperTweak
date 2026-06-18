package com.takekazex.hypertweak.ui.page

import androidx.navigation3.runtime.NavKey

sealed interface Route : NavKey {
    data object Main : Route
    data object About : Route
    data object Credits : Route
    data object HiddenFeatures : Route
    data object AppShortcuts : Route
    data object DebugLogs : Route
}
