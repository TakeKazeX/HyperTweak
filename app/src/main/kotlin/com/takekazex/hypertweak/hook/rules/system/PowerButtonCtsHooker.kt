package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method

/**
 * Re-binds the long-press power button to Circle to Search (长按电源键 → 即圈即搜).
 *
 * On the OS4 baseline the long-press power action is bound in system_server on two stacked
 * layers (see the reverse-engineering workspace, framework-services-2e880646):
 * - `com.android.server.input.shortcut.singlekeyrule.PowerKeyRule#onMiuiLongPress(long)` — the
 *   MIUI 快捷手势 layer driven by `Settings.System.long_press_power_key`; it preempts the AOSP
 *   layer whenever the user configured a function;
 * - `PhoneWindowManager#powerLongPress(long)` — the AOSP fallback driven by
 *   `Settings.Global.power_button_long_press` (1 = power menu, 2/3 = shutdown, 4 = voice
 *   assist, 5 = assistant), reached through `OriginalPowerKeyRuleBridge` when MIUI does not
 *   own the long press.
 *
 * Both are intercepted before their original dispatch: the power key is marked handled and
 * [ContextualSearchSystemHooker.startFromSystemServer] starts the contextual-search service,
 * which (through the CTS bridge) resolves the Google package and launches Circle to Search.
 * The preference is re-read live at dispatch time, so turning the re-bind off takes effect
 * without a reboot; turning it on requires a reboot so the system-server hooks (and the CTS
 * bridge gate in `ContextualSearchSystemHooker`) install.
 */
object PowerButtonCtsHooker : StaticHooker() {
    override val hookerName = "PowerButtonCts"
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "PowerButtonCTS"
    private const val TARGET_MIUI = "PowerKeyRule#onMiuiLongPress(long)"
    private const val TARGET_AOSP = "PhoneWindowManager#powerLongPress(long)"

    @Volatile
    private var setPowerKeyHandledMethod: Method? = null

    override fun onInit() {
        if (!enabled()) {
            DebugLog.hookSkipped(SCOPE, "power button long press", "Circle to Search disabled")
            return
        }
        hookMiuiPowerKeyRule()
        hookAospPowerLongPress()
    }

    private fun hookMiuiPowerKeyRule() {
        val ruleClass = "com.android.server.input.shortcut.singlekeyrule.PowerKeyRule"
            .toClassOrNull()
            ?: return DebugLog.hookSkipped(SCOPE, TARGET_MIUI, "class not found")
        runCatching {
            // OS4 grew a `singleKeyGestureEvent` parameter, so resolve by name and a trailing
            // `long` instead of pinning one signature: `(long)` on older platforms,
            // `(Object, long)` here.
            val method = ruleClass.declaredMethods
                .firstOrNull {
                    it.name == "onMiuiLongPress" &&
                        it.parameterTypes.lastOrNull() == Long::class.javaPrimitiveType
                }
                ?: return@runCatching DebugLog.hookSkipped(SCOPE, TARGET_MIUI, "method not found")
            method.isAccessible = true
            // Protected and small, so ART is free to inline it; deoptimize first or the hook
            // never fires.
            deoptimize(method)
            method.hook("power_button_cts_miui_long_press") {
                before { param ->
                    if (!enabled()) return@before
                    DebugLog.i(SCOPE, "MIUI long-press power -> Circle to Search")
                    markPowerKeyHandled(policyFrom(param.thisObject))
                    if (ContextualSearchSystemHooker.startFromSystemServer()) {
                        // Skip the MIUI function dispatch and the AOSP fallback underneath.
                        param.result = null
                    }
                }
            }
        }.onFailure { t ->
            DebugLog.hookFailed(SCOPE, TARGET_MIUI, t)
        }
    }

    private fun hookAospPowerLongPress() {
        val policyClass = "com.android.server.policy.PhoneWindowManager"
            .toClassOrNull()
            ?: return DebugLog.hookSkipped(SCOPE, TARGET_AOSP, "class not found")
        runCatching {
            val method = policyClass.getDeclaredMethod(
                "powerLongPress",
                Long::class.javaPrimitiveType
            ).apply { isAccessible = true }
            deoptimize(method)
            method.hook("power_button_cts_aosp_long_press") {
                before { param ->
                    if (!enabled()) return@before
                    DebugLog.i(SCOPE, "AOSP long-press power -> Circle to Search")
                    markPowerKeyHandled(param.thisObject)
                    if (ContextualSearchSystemHooker.startFromSystemServer()) {
                        // Skip the behavior switch (power menu / shutdown / assistant).
                        param.result = null
                    }
                }
            }
        }.onFailure { t ->
            DebugLog.hookFailed(SCOPE, TARGET_AOSP, t)
        }
    }

    private fun enabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_POWER_BUTTON_CTS, false)

    /**
     * Mirrors what both originals do on their own long-press paths
     * (`setPowerKeyHandled(true)`), so the key-up / multi-press bookkeeping still treats the
     * power key as consumed by this long press.
     */
    private fun markPowerKeyHandled(policy: Any?) {
        if (policy == null) return
        runCatching {
            val method = setPowerKeyHandledMethod ?: policy.javaClass
                .getMethod("setPowerKeyHandled", Boolean::class.javaPrimitiveType)
                .also { setPowerKeyHandledMethod = it }
            method.invoke(policy, true)
        }.onFailure { t ->
            DebugLog.w(SCOPE, "failed to mark power key handled", t)
        }
    }

    /** The `WindowManagerPolicy` the rule holds, which is the MIUI `PhoneWindowManager`. */
    private fun policyFrom(rule: Any?): Any? {
        if (rule == null) return null
        return runCatching {
            rule.javaClass.getDeclaredField("mWindowManagerPolicy")
                .apply { isAccessible = true }
                .get(rule)
        }.getOrNull()
    }
}
