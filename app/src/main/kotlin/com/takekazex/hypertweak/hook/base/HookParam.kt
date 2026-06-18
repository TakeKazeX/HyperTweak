package com.takekazex.hypertweak.hook.base

import android.content.pm.ApplicationInfo
import android.content.Context

/**
 * Module-level context passed to each hooker, storing metadata about the current process.
 * Renamed from HookParam to avoid collision with EzHookTool's HookParam.
 */
data class ModuleContext(
    val processName: String,
    val packageName: String,
    val isSystemServer: Boolean,
    val isFirstPackage: Boolean = false,
    val isPackageReady: Boolean = false,
    val appInfo: ApplicationInfo? = null,
    val appContext: Context? = null
) {
    val isMainProcess: Boolean
        get() = processName == packageName
}
