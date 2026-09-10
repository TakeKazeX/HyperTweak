package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.ContentResolver
import android.provider.Settings
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import java.lang.reflect.Method

/**
 * Applies the selected Security Center low-battery warning mode without changing the system
 * setting itself.
 *
 * The dialog gate reads `Settings.System.low_battery_dialog_disabled`; the sound path reads
 * `low_battery_sound` from the System table on the current build and from the Global table while
 * the notification channel is prepared. Only those reads are overridden, so other Settings users
 * and the persisted ROM configuration remain untouched.
 */
object LowBatteryWarningHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "LowBatteryWarning"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val DIALOG_DISABLED = "low_battery_dialog_disabled"
    private const val SOUND = "low_battery_sound"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        val mode = selectedMode()
        if (mode == Preferences.SECURITY_CENTER_LOW_BATTERY_FOLLOW) {
            DebugLog.hookSkipped(TAG, "low-battery warning", "follow system")
            return
        }

        var installed = 0
        installed += hookIntReads(Settings.System::class.java)
        if (mode == Preferences.SECURITY_CENTER_LOW_BATTERY_SILENT) {
            installed += hookStringReads(Settings.System::class.java)
            installed += hookStringReads(Settings.Global::class.java)
        }

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "low-battery warning", "Settings read methods not found")
        } else {
            DebugLog.i(TAG, "low-battery mode=$mode boundaries=$installed")
        }
    }

    private fun selectedMode(): Int {
        if (Preferences.contains(Preferences.KEY_SECURITY_CENTER_LOW_BATTERY_MODE)) {
            return Preferences.getInt(
                Preferences.KEY_SECURITY_CENTER_LOW_BATTERY_MODE,
                Preferences.SECURITY_CENTER_LOW_BATTERY_FOLLOW
            ).coerceIn(
                Preferences.SECURITY_CENTER_LOW_BATTERY_FOLLOW,
                Preferences.SECURITY_CENTER_LOW_BATTERY_SILENT
            )
        }

        // Version 1 stored one boolean. Its enabled state meant both dialog and sound were hidden.
        return if (
            Preferences.contains(Preferences.KEY_SECURITY_CENTER_HIDE_LOW_BATTERY_WARNING) &&
            Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_HIDE_LOW_BATTERY_WARNING, false)
        ) {
            Preferences.SECURITY_CENTER_LOW_BATTERY_SILENT
        } else {
            Preferences.SECURITY_CENTER_LOW_BATTERY_FOLLOW
        }
    }

    private fun hookIntReads(owner: Class<*>): Int {
        val methods = owner.declaredMethods.filter { method ->
            method.name == "getInt" &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterTypes.size >= 2 &&
                method.parameterTypes[0] == ContentResolver::class.java &&
                method.parameterTypes[1] == String::class.java
        }
        methods.forEach { method ->
            install(method, "${owner.name}.getInt") { param ->
                if (
                    param.args.getOrNull(1) as? String == DIALOG_DISABLED &&
                    selectedMode() != Preferences.SECURITY_CENTER_LOW_BATTERY_FOLLOW
                ) {
                    param.result = 1
                }
            }
        }
        return methods.size
    }

    private fun hookStringReads(owner: Class<*>): Int {
        val methods = owner.declaredMethods.filter { method ->
            method.name == "getString" &&
                method.returnType == String::class.java &&
                method.parameterTypes.contentEquals(
                    arrayOf(ContentResolver::class.java, String::class.java)
                )
        }
        methods.forEach { method ->
            install(method, "${owner.name}.getString") { param ->
                if (
                    param.args.getOrNull(1) as? String == SOUND &&
                    selectedMode() == Preferences.SECURITY_CENTER_LOW_BATTERY_SILENT
                ) {
                    param.result = null
                }
            }
        }
        return methods.size
    }

    private fun install(method: Method, target: String, action: (HookParam) -> Unit) {
        runCatching {
            method.isAccessible = true
            deoptimize(method)
            method.hook("low_battery_warning_${method.declaringClass.name}_${method.name}_${method.parameterCount}") {
                before { param ->
                    HookFailurePolicy.open(TAG, "$target.before", Unit) {
                        action(param)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, method.toGenericString(), it)
        }
    }
}
