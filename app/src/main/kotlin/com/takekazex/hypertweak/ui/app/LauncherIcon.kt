package com.takekazex.hypertweak.ui.app

import com.takekazex.hypertweak.util.logging.DebugLog
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal fun setLauncherIconVisible(context: Context, visible: Boolean) {
    runCatching {
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "com.takekazex.hypertweak.MainActivityAlias"),
            state,
            PackageManager.DONT_KILL_APP
        )
    }.onFailure { DebugLog.w("LauncherIcon", "set launcher icon visibility failed", it) }
}
