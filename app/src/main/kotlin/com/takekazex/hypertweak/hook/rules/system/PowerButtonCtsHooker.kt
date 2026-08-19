package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method

/**
 * Re-binds the long-press power button to a configurable action (长按电源键操作):
 * Circle to Search (即圈即搜) or the default digital assistant (默认助理, e.g. Google
 * Assistant / Gemini / 小爱), with optional haptic feedback when the custom action triggers.
 *
 * On the OS4 baseline the long-press power action is bound in system_server on two stacked
 * layers (see the reverse-engineering workspace, framework-services-2e880646):
 * - `com.android.server.input.shortcut.singlekeyrule.PowerKeyRule#onMiuiLongPress(Object, long)`
 *   — the MIUI 快捷手势 layer driven by `Settings.System.long_press_power_key`; it preempts the
 *   AOSP layer whenever the user configured a function;
 * - `PhoneWindowManager#powerLongPress(long)` — the AOSP fallback driven by
 *   `Settings.Global.power_button_long_press` (1 = power menu, 2/3 = shutdown, 4 = voice
 *   assist, 5 = assistant), reached through `OriginalPowerKeyRuleBridge` when MIUI does not
 *   own the long press.
 *
 * Both are intercepted before their original dispatch: the power key is marked handled, the
 * selected action runs, and the haptic (the platform's own `LONG_PRESS_POWER_BUTTON` effect,
 * through the policy's `performHapticFeedback`) plays when the custom action actually fired.
 * Circle to Search goes through [ContextualSearchSystemHooker.startFromSystemServer]; the
 * default assistant goes through `PhoneWindowManager.launchAssistAction(null, -2, eventTime, 6)`
 * — the exact path the AOSP "assistant" long-press (setting 5) uses on this build, so the
 * platform assist pipeline engages the default assistant's voice UI properly (CLAUDE.md notes
 * bare activity launches of Gemini/ChatGPT self-terminate without an assist-framework session;
 * this path creates one). The preferences are re-read live at dispatch time, so changing the
 * action or the haptic toggle takes effect without a reboot; turning the feature on from
 * disabled requires a reboot so the system-server hooks (and the CTS bridge gate in
 * `ContextualSearchSystemHooker`) install.
 */
object PowerButtonCtsHooker : StaticHooker() {
    override val hookerName = "PowerButtonCts"
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "PowerButtonCTS"
    private const val TARGET_MIUI = "PowerKeyRule#onMiuiLongPress(long)"
    private const val TARGET_AOSP = "PhoneWindowManager#powerLongPress(long)"

    /** The `invocation_type` the platform's own power-button assistant path passes. */
    private const val INVOCATION_TYPE_POWER_ASSIST = 6

    /** `HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON`; @SystemApi so absent from the stub jar. */
    private const val HAPTIC_LONG_PRESS_POWER_BUTTON = 10003

    @Volatile
    private var setPowerKeyHandledMethod: Method? = null
    @Volatile
    private var launchAssistActionMethod: Method? = null
    @Volatile
    private var performHapticFeedbackMethod: Method? = null

    override fun onInit() {
        if (Preferences.powerButtonAction() == Preferences.POWER_BUTTON_ACTION_DISABLED) {
            DebugLog.hookSkipped(SCOPE, "power button long press", "action disabled")
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
                    if (!dispatchEnabled()) return@before
                    val eventTime = param.args.lastOrNull() as? Long ?: 0L
                    val policy = policyFrom(param.thisObject)
                    if (dispatchAction("MIUI", policy, eventTime)) {
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
                    if (!dispatchEnabled()) return@before
                    val eventTime = param.args.firstOrNull() as? Long ?: 0L
                    if (dispatchAction("AOSP", param.thisObject, eventTime)) {
                        // Skip the behavior switch (power menu / shutdown / assistant).
                        param.result = null
                    }
                }
            }
        }.onFailure { t ->
            DebugLog.hookFailed(SCOPE, TARGET_AOSP, t)
        }
    }

    private fun dispatchEnabled(): Boolean =
        Preferences.powerButtonAction() != Preferences.POWER_BUTTON_ACTION_DISABLED

    private fun hapticEnabled(): Boolean =
        Preferences.getBoolean(
            Preferences.KEY_POWER_BUTTON_HAPTIC,
            Preferences.DEFAULT_POWER_BUTTON_HAPTIC
        )

    /**
     * Runs the selected long-press power action. The power key is always marked handled so the
     * key-up / multi-press bookkeeping still treats it as consumed by this long press; the
     * haptic plays only when the custom action actually fired, so a failed dispatch degrades to
     * the original system action without a phantom vibration.
     */
    private fun dispatchAction(
        layer: String,
        policy: Any?,
        eventTime: Long
    ): Boolean {
        markPowerKeyHandled(policy)
        val dispatched = when (Preferences.powerButtonAction()) {
            Preferences.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH -> {
                DebugLog.i(SCOPE, "$layer long-press power -> Circle to Search")
                ContextualSearchSystemHooker.startFromSystemServer()
            }
            Preferences.POWER_BUTTON_ACTION_DEFAULT_ASSISTANT -> {
                DebugLog.i(SCOPE, "$layer long-press power -> default assistant")
                launchDefaultAssistant(policy, eventTime)
            }
            else -> false
        }
        if (dispatched && hapticEnabled()) {
            performHapticFeedback(policy)
        }
        return dispatched
    }

    /**
     * Launches the user's default digital assistant (Google Assistant / Gemini / 小爱…) through
     * the platform assist pipeline: `PhoneWindowManager.launchAssistAction(String, int, long,
     * int)` with the same arguments the AOSP "assistant" long-press uses on this build. The
     * method is private on the current baseline, so it is resolved up the class hierarchy (the
     * runtime policy is the MIUI subclass) and called reflectively.
     */
    private fun launchDefaultAssistant(policy: Any?, eventTime: Long): Boolean {
        if (policy == null) return false
        return runCatching {
            val method = launchAssistActionMethod ?: findMethod(
                policy.javaClass,
                "launchAssistAction",
                String::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )?.also { launchAssistActionMethod = it }
                ?: throw NoSuchMethodException("launchAssistAction(String,int,long,int)")
            method.invoke(policy, null, -2, eventTime, INVOCATION_TYPE_POWER_ASSIST)
            true
        }.onFailure { t ->
            DebugLog.w(SCOPE, "default assistant launch failed", t)
        }.getOrDefault(false)
    }

    /**
     * Plays the same power-long-press haptic the system uses for its own long-press actions
     * (`LONG_PRESS_POWER_BUTTON`), via the policy's private `performHapticFeedback(int, String)`
     * so the platform's own telemetry / vibrator routing applies.
     */
    private fun performHapticFeedback(policy: Any?) {
        if (policy == null) return
        runCatching {
            val method = performHapticFeedbackMethod ?: findMethod(
                policy.javaClass,
                "performHapticFeedback",
                Int::class.javaPrimitiveType,
                String::class.java
            )?.also { performHapticFeedbackMethod = it }
                ?: throw NoSuchMethodException("performHapticFeedback(int,String)")
            method.invoke(
                policy,
                HAPTIC_LONG_PRESS_POWER_BUTTON,
                "HyperTweak - Power Long Press"
            )
            DebugLog.i(SCOPE, "power long press haptic played")
        }.onFailure { t ->
            DebugLog.w(SCOPE, "power long press haptic failed", t)
        }
    }

    /** Walks [type] and its superclasses for a declared method; private callers must set access. */
    private fun findMethod(type: Class<*>, name: String, vararg params: Class<*>?): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                current.getDeclaredMethod(name, *params.filterNotNull().toTypedArray())
            }.getOrNull()?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

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