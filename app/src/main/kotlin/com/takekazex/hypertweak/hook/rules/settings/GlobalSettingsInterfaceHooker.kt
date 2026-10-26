package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.StaticFieldWriter
import java.lang.reflect.Modifier

/** Enables the stock international Settings branches inside com.android.settings only. */
object GlobalSettingsInterfaceHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingSettingsGlobalInterface by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, false)
    }

    private const val TAG = "GlobalSettingsInterface"
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val MIUI_BUILD_CLASS = "miui.os.Build"
    private const val INTERNATIONAL_BUILD_FIELD = "IS_INTERNATIONAL_BUILD"

    override fun onHook() {
        if (hookParam.packageName != SETTINGS_PACKAGE || !isMainProcess) {
            DebugLog.hookSkippedDebug(TAG, SETTINGS_PACKAGE, "not the Settings main process")
            return
        }
        if (!settingSettingsGlobalInterface) {
            DebugLog.hookSkippedDebug(TAG, "international Settings interface", "disabled")
            return
        }

        try {
            val buildClass = Class.forName(MIUI_BUILD_CLASS, true, null)
            val field = HostAccess.requireDeclaredField(buildClass, INTERNATIONAL_BUILD_FIELD, accessible = true).apply {
                require(type == java.lang.Boolean.TYPE && Modifier.isStatic(modifiers)) {
                    "$MIUI_BUILD_CLASS#$INTERNATIONAL_BUILD_FIELD is not a static boolean"
                }
            }

            // Follow HyperCeiler's Settings hook: switch the flag consumed by international UI
            // branches. Build can already be initialized in the forked app process, so write its
            // process-local static storage with the Android 16-compatible field writer.
            if (!field.getBoolean(null)) {
                StaticFieldWriter.setBoolean(field, true)
            }
            if (field.getBoolean(null)) {
                DebugLog.i(TAG, "Settings IS_INTERNATIONAL_BUILD enabled")
            } else {
                DebugLog.w(TAG, "Could not enable Settings IS_INTERNATIONAL_BUILD")
            }
        } catch (t: Throwable) {
            DebugLog.e(TAG, "Failed to enable Settings IS_INTERNATIONAL_BUILD", t)
        }
    }
}
