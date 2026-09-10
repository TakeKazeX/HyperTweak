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
 * Suppresses Security Center's low-battery warning without changing the system setting itself.
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
        if (!Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_HIDE_LOW_BATTERY_WARNING, false)) {
            DebugLog.hookSkipped(TAG, "low-battery warning", "disabled")
            return
        }

        var installed = 0
        installed += hookIntReads(Settings.System::class.java)
        installed += hookStringReads(Settings.System::class.java)
        installed += hookStringReads(Settings.Global::class.java)

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "low-battery warning", "Settings read methods not found")
        } else {
            DebugLog.i(TAG, "low-battery dialog and sound reads suppressed boundaries=$installed")
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
                if (param.args.getOrNull(1) as? String == DIALOG_DISABLED) {
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
                if (param.args.getOrNull(1) as? String == SOUND) {
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
