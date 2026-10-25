package com.takekazex.hypertweak.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import com.takekazex.hypertweak.app.AppServicePorts

internal val LocalAppServices = staticCompositionLocalOf<AppServicePorts> {
    error("AppServicePorts was not provided by the app shell")
}
