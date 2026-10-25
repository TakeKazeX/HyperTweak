package com.takekazex.hypertweak.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import com.takekazex.hypertweak.settings.contract.AppSettingsStore

/** Supplied by the app shell; pages share its effective readers and acknowledged writer. */
internal val LocalAppSettingsStore = staticCompositionLocalOf<AppSettingsStore> {
    error("AppSettingsStore was not provided by the app shell")
}
