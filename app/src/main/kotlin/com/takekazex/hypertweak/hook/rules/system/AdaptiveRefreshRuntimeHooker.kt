package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Opens the system_server-side gate for the 自适应刷新率Pro (Mimotion PWM) mode.
 *
 * `com.android.server.display.DisplayFeatureManagerService` (miui-services.jar) bakes
 * `static final MIMOTION_PWM_SUPPORTED` at class load from
 * `SystemProperties.getBoolean("ro.display.enable_pwm_switch", false)` and only runs its boot-time
 * `setDimmingMode()` (reading `Settings.Secure mimotion_pwm_enable` and pushing displayfeature
 * effect 20 = SCREEN_Dimming_Mode) when that static is true. On builds where the vendor prop is
 * unset the service therefore never re-applies a saved mode after reboot.
 *
 * Forcing the same prop read to true when [Preferences.unlockAdaptiveRefreshPro] is on makes the
 * static field initialise true on the next system_server start, so `setDimmingMode()` re-applies
 * whatever mode the user left in Secure (2 = PWM on). The Settings row itself is handled by
 * [com.takekazex.hypertweak.hook.rules.settings.AdaptiveRefreshSettingsHooker]; toggling it while
 * running already pushes effect 20 through the binder immediately, this hook only restores it after
 * a reboot. Per-call preference read: attach-time value decides the boot that follows, so the
 * change is effective from the **next reboot** after the module switch is on.
 */
object AdaptiveRefreshRuntimeHooker : StaticHooker() {
    private const val TAG = "AdaptiveRefreshRuntime"
    private const val PROP_PWM_SWITCH = "ro.display.enable_pwm_switch"

    override fun onHook() {
        val systemProperties = "android.os.SystemProperties".toClassOrNull() ?: return
        val method = systemProperties.declaredMethods.firstOrNull {
            it.name == "getBoolean" &&
                it.parameterTypes.contentEquals(
                    arrayOf(String::class.java, java.lang.Boolean.TYPE)
                ) &&
                it.returnType == java.lang.Boolean.TYPE
        } ?: run {
            DebugLog.w(TAG, "SystemProperties.getBoolean(String, boolean) not found; skipped")
            return
        }
        deoptimize(method)
        method.hook("adaptive_refresh_runtime_gate") { after { param ->
            val key = param.args.getOrNull(0) as? String ?: return@after
            if (key != PROP_PWM_SWITCH) return@after
            if (param.result == true) return@after
            runCatching {
                if (Preferences.unlockAdaptiveRefreshPro()) {
                    param.result = true
                }
            }.onFailure { t ->
                DebugLog.w(TAG, "failed to force $PROP_PWM_SWITCH in system_server", t)
            }
        } }
        DebugLog.i(TAG, "armed $PROP_PWM_SWITCH -> true for DisplayFeatureManagerService boot-time dimming mode")
    }
}
