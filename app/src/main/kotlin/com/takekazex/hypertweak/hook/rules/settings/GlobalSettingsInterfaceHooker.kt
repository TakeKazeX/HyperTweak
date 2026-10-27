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

    private var buildFlag: java.lang.reflect.Field? by generationState { null }
    private var originalFlag: Boolean? by generationState { null }

    override fun onPrepareHotReload() {
        val field = buildFlag ?: return
        originalFlag?.let { StaticFieldWriter.setBoolean(field, it) }
    }

    fun refreshSettings(): Boolean {
        if (hookParam.packageName != SETTINGS_PACKAGE || !isMainProcess) return true
        val field = buildFlag ?: return false
        return runCatching {
            val desired = if (settingSettingsGlobalInterface) true else originalFlag ?: return false
            if (field.getBoolean(null) != desired) StaticFieldWriter.setBoolean(field, desired)
            field.getBoolean(null) == desired
        }.onFailure { DebugLog.w(TAG, "could not refresh international Settings flag", it) }.getOrDefault(false)
    }

    override fun onHook() {
        if (hookParam.packageName != SETTINGS_PACKAGE || !isMainProcess) {
            DebugLog.hookSkippedDebug(TAG, SETTINGS_PACKAGE, "not the Settings main process")
            return
        }

        try {
            val buildClass = Class.forName(MIUI_BUILD_CLASS, true, null)
            val field = HostAccess.requireDeclaredField(buildClass, INTERNATIONAL_BUILD_FIELD, accessible = true).apply {
                require(type == java.lang.Boolean.TYPE && Modifier.isStatic(modifiers)) {
                    "$MIUI_BUILD_CLASS#$INTERNATIONAL_BUILD_FIELD is not a static boolean"
                }
            }

            if (buildFlag == null) {
                originalFlag = field.getBoolean(null)
                buildFlag = field
            }
            if (!refreshSettings()) DebugLog.w(TAG, "could not apply international Settings flag")
        } catch (t: Throwable) {
            DebugLog.e(TAG, "Failed to enable Settings IS_INTERNATIONAL_BUILD", t)
        }
    }
}
