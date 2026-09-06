package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Reveals the 显示与亮度「自适应刷新率Pro」row (`mimotion_pwm_enable`) on devices whose vendor build
 * leaves `ro.display.enable_pwm_switch` unset.
 *
 * `com.android.settings.MiuiDisplaySettings` bakes `static final MIMOTION_PWM_SUPPORTED` once at
 * class load from `SystemProperties.getBoolean("ro.display.enable_pwm_switch", false)`; when false,
 * `onCreate` `removePreference`s the checkbox (and never wires its toggle listener), so the row is
 * simply absent. Forcing that single prop read to true whenever
 * [Preferences.unlockAdaptiveRefreshPro] is on makes the stock code keep the row, register the
 * Secure `mimotion_pwm_enable` observer and enable the toggle: flipping it then writes
 * `mimotion_pwm_enable = 2` and pushes `SCREEN_Dimming_Mode` (displayfeature effect 20) through the
 * normal Settings path.
 *
 * Because the gate is baked at class load, the row appears on the next `com.android.settings`
 * process start (or first open of the display page in a fresh process) with the switch on. See
 * [com.takekazex.hypertweak.hook.rules.system.AdaptiveRefreshRuntimeHooker] for the system_server
 * boot-time re-apply, and docs/FEATURE_DETAIL.md for the underlying mechanism.
 */
object AdaptiveRefreshSettingsHooker : StaticHooker() {
    private const val TAG = "AdaptiveRefreshSettings"
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
        method.hook("adaptive_refresh_pwm_support") { after { param ->
            val key = param.args.getOrNull(0) as? String ?: return@after
            if (key != PROP_PWM_SWITCH) return@after
            if (param.result == true) return@after
            runCatching {
                if (Preferences.unlockAdaptiveRefreshPro()) {
                    param.result = true
                }
            }.onFailure { t ->
                DebugLog.w(TAG, "failed to force $PROP_PWM_SWITCH in Settings", t)
            }
        } }
        DebugLog.i(TAG, "armed $PROP_PWM_SWITCH -> true for the 显示与亮度 mimotion_pwm_enable row")
    }
}
