package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

/**
 * Re-binds the long-press power button to a configurable action (长按电源键操作):
 * Circle to Search (即圈即搜) or the default digital assistant (默认助理, e.g. Google
 * Assistant / Gemini / 小爱).
 *
 * The long press is bound in system_server on two *stacked* layers, and which one runs
 * depends on the user's MIUI shortcut setting:
 *
 * - The MIUI layer, `com.android.server.input.shortcut.singlekeyrule.PowerKeyRule` in
 *   `miui-services.jar` (loaded through `SYSTEMSERVERCLASSPATH`). It reaches the key through
 *   the shared `SingleKeyGestureDetector`, which invokes the rule it selected:
 *   `MiuiSingleKeyRule#onKeyGesture(SingleKeyGestureEvent)` →
 *   `PowerKeyRule#onMiuiLongPress(Object, long)`. When `Settings.System.long_press_power_key`
 *   names a function, `supportMiuiLongPress()` accepts the gesture and MIUI dispatches it
 *   itself through `ShortCutActionsUtils.triggerFunction` (XiaoAI, for
 *   `launch_voice_assistant`).
 * - The AOSP layer, `com.android.server.policy.PhoneWindowManager$PowerKeyRule` in
 *   `services.jar`, driven by `Settings.Global.power_button_long_press`. MIUI reaches it only
 *   by *declining*: `onMiuiLongPress` forwards the event to
 *   `OriginalPowerKeyRuleBridge#onKeyGesture` when `supportMiuiLongPress()` is false or its
 *   own trigger returns false.
 *
 * Verified on the OS4.0.0.30 baseline: `dumpsys window policy` lists exactly one keyCode-26
 * rule and it is MIUI's, and a long press logs
 * `MiuiInputKeyEventLog: shortcut:long_press_power_key trigger function:launch_voice_assistant`.
 * Hooking only the AOSP class therefore installs hooks the press never reaches — the key is
 * consumed one layer above. Both layers are hooked, plus a log-only hook on the MIUI dispatcher
 * as the evidence path that would have shown this immediately, and a per-gesture claim makes the
 * layered hooks dispatch exactly once.
 *
 * The MIUI hook sits on `onMiuiLongPress`, **not** on the dispatcher that calls it, even though
 * the dispatcher is the more robust frame. `MiuiSingleKeyRule#onKeyGesture` runs
 * `mMiuiShortcutTriggerHelper.notifyLongPressed(keyCode)` before dispatching, and that recorded
 * key code is what `interceptKeyByLongPress` uses on the key-up to suppress the *short*-press
 * function. Skipping the dispatcher body would drop that bookkeeping and let the power key-up
 * act as a short press on top of the long-press action. Skipping `onMiuiLongPress` instead
 * suppresses only MIUI's own long-press function, which is exactly the intent.
 *
 * Each dispatch marks the power key handled, runs the selected action, and plays the platform's
 * own `LONG_PRESS_POWER_BUTTON` haptic through the policy's `performHapticFeedback`. Circle to
 * Search goes through [ContextualSearchSystemHooker.startFromSystemServer]. The default assistant
 * goes through `PhoneWindowManager.launchAssistAction(null, -2, eventTime, 6)` — the same call the
 * AOSP "assistant" long-press (setting 5) makes on this build, so the platform assist pipeline
 * creates a real assist session (bare activity launches of Gemini/ChatGPT self-terminate without
 * one) — but only while SystemUI's `AssistManager` can actually launch the selected assistant; see
 * [DefaultAssistantHooker] for the HyperOS state that breaks it and for the direct-launch fallback
 * used otherwise. The action is read live at dispatch time, so switching between actions (or off)
 * takes effect without a reboot once the hooks are installed; turning the feature on from disabled
 * still needs a reboot for those hooks (and the CTS bridge gate in
 * `ContextualSearchSystemHooker`) to install. The power-long-press haptic has no preference: it
 * fires whenever a custom action is bound.
 */
object PowerButtonCtsHooker : StaticHooker() {
    override val hookerName = "PowerButtonCts"
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "PowerButtonCTS"

    /**
     * The MIUI rule that owns the power key. It lives in `miui-services.jar`, not in
     * `services.jar`, so searching only the AOSP artifact reports it missing.
     */
    private const val MIUI_POWER_RULE_CLASS =
        "com.android.server.input.shortcut.singlekeyrule.PowerKeyRule"

    /**
     * The dispatcher every MIUI single-key rule inherits. `SingleKeyGestureDetector` invokes it
     * through the `SingleKeyRule` base type on the rule it selected, so this is the first frame a
     * power press reaches — and it carries the key code, which lets the hook ignore the camera,
     * volume, AI-key and back rules that share the same dispatcher.
     */
    private const val MIUI_SINGLE_KEY_RULE_CLASS =
        "com.android.server.policy.MiuiSingleKeyRule"

    private const val AOSP_POWER_KEY_RULE_CLASS =
        "com.android.server.policy.PhoneWindowManager\$PowerKeyRule"
    private const val PHONE_WINDOW_MANAGER_CLASS =
        "com.android.server.policy.PhoneWindowManager"

    private const val TARGET_MIUI_DISPATCH = "MiuiSingleKeyRule#onKeyGesture(SingleKeyGestureEvent)"
    private const val TARGET_MIUI_LONG_PRESS = "PowerKeyRule#onMiuiLongPress(Object,long)"
    private const val TARGET_AOSP_GESTURE =
        "PhoneWindowManager\$PowerKeyRule#onKeyGesture(SingleKeyGestureEvent)"
    private const val TARGET_AOSP_LONG_PRESS = "PhoneWindowManager#powerLongPress(long)"

    /** `KeyEvent.KEYCODE_POWER`; the only key these rules must treat as ours. */
    private const val KEYCODE_POWER = 26

    /**
     * `SingleKeyGestureEvent`'s action type for a long press: the value `onKeyGesture` switches
     * on to reach the long-press branch. Press (0) and very-long-press (2) stay untouched.
     */
    private const val GESTURE_TYPE_LONG_PRESS = 1

    /** The `invocation_type` the platform's own power-button assistant path passes. */
    private const val INVOCATION_TYPE_POWER_ASSIST = 6

    /** `HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON`; @SystemApi so absent from the stub jar. */
    private const val HAPTIC_LONG_PRESS_POWER_BUTTON = 10003

    /**
     * The gesture start time of the last long press this hooker dispatched.
     *
     * The layers are nested, so one press can reach several of the hooks below: MIUI's
     * `onMiuiLongPress` calls the AOSP rule, and that rule's `onKeyGesture` calls `onLongPress`,
     * which calls `powerLongPress`. Each press has a distinct `startTime` (the detector's
     * down time), so the first hook to see a press claims it and the nested ones decline. This
     * is deliberately not a thread-local held across hooks: a claim must survive the frames the
     * original body runs, including when it is the *outer* hook that declined.
     */
    private val claimedGestureStartTime = AtomicLong(Long.MIN_VALUE)

    @Volatile
    private var setPowerKeyHandledMethod: Method? = null
    @Volatile
    private var launchAssistActionMethod: Method? = null
    @Volatile
    private var performHapticFeedbackMethod: Method? = null
    @Volatile
    private var systemContextCache: Context? = null

    override fun onHook() {
        if (Preferences.powerButtonAction() == Preferences.POWER_BUTTON_ACTION_DISABLED) {
            DebugLog.hookSkipped(SCOPE, "power button long press", "action disabled")
            return
        }
        // The MIUI layer is the one that owns the key on this baseline; its hook is installed
        // first so the AOSP hooks stay the fallback rather than the primary.
        observeMiuiGestureDispatcher()
        hookMiuiPowerRuleLongPress()
        hookAospPowerKeyRuleGesture()
        hookAospPowerLongPress()
    }

    /**
     * Evidence-only hook on the MIUI dispatcher `SingleKeyGestureDetector` invokes.
     *
     * It installs nothing that changes behaviour: it logs the press (and whether the action ran)
     * so a build where the press does not reach the hooks is immediately visible. A previous
     * revision of this file hooked only the AOSP class and reported `HOOK_OK` for every hook while
     * the long press did nothing at all; a passive log on the frame the detector actually calls
     * would have shown that at once.
     *
     * The dispatch itself is deliberately *not* done here — see the class comment on why skipping
     * this method's body would drop `notifyLongPressed` and leak a short press.
     */
    private fun observeMiuiGestureDispatcher() {
        val ruleClass = MIUI_SINGLE_KEY_RULE_CLASS.toClassOrNull()
            ?: return DebugLog.hookSkipped(SCOPE, TARGET_MIUI_DISPATCH, "class not found")
        runCatching {
            val method = ruleClass.declaredMethods.firstOrNull {
                it.name == "onKeyGesture" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name.endsWith("SingleKeyGestureEvent")
            } ?: return@runCatching DebugLog.hookSkipped(
                SCOPE, TARGET_MIUI_DISPATCH, "method not found"
            )
            method.isAccessible = true
            // This is the method SingleKeyGestureDetector invokes through the base rule type.
            // It is small and hot, so ART may inline it before the hook is installed.
            deoptimize(method)
            method.hook("power_button_cts_miui_observe") {
                before { param ->
                    val event = param.args.firstOrNull() ?: return@before
                    // This dispatcher serves every MIUI single-key rule; only the power key is ours.
                    if (readEventKeyCode(event) != KEYCODE_POWER) return@before
                    if (readEventType(event) != GESTURE_TYPE_LONG_PRESS) return@before
                    DebugLog.i(
                        SCOPE,
                        "MIUI power long press observed enabled=${dispatchEnabled()}"
                    )
                    // No result is set: the original body must still run its notifyLongPressed
                    // bookkeeping and reach the long-press hook installed below.
                }
            }
        }.onFailure { t ->
            DebugLog.hookFailed(SCOPE, TARGET_MIUI_DISPATCH, t)
        }
    }

    /**
     * The MIUI long-press entry point the dispatcher calls. Hooking it suppresses MIUI's own
     * function dispatch (XiaoAI for `launch_voice_assistant`) without touching the dispatcher's
     * key-up bookkeeping.
     */
    private fun hookMiuiPowerRuleLongPress() {
        val ruleClass = MIUI_POWER_RULE_CLASS.toClassOrNull()
            ?: return DebugLog.hookSkipped(SCOPE, TARGET_MIUI_LONG_PRESS, "class not found")
        runCatching {
            // Resolve by name plus a trailing `long` rather than pinning one signature: the
            // release ships `(Object, long)` while older layouts had `(long)`.
            val method = ruleClass.declaredMethods.firstOrNull {
                it.name == "onMiuiLongPress" &&
                    it.parameterTypes.lastOrNull() == Long::class.javaPrimitiveType
            } ?: return@runCatching DebugLog.hookSkipped(
                SCOPE, TARGET_MIUI_LONG_PRESS, "method not found"
            )
            method.isAccessible = true
            // Protected and small, so ART is free to inline it; deoptimize first or the hook
            // never fires.
            deoptimize(method)
            method.hook("power_button_cts_miui_long_press") {
                before { param ->
                    if (!dispatchEnabled()) return@before
                    if (dispatchMiuiLongPress(param.args, param.thisObject)) {
                        // Skip the MIUI function dispatch and the AOSP fallback underneath.
                        param.result = null
                    }
                }
            }
        }.onFailure { t ->
            DebugLog.hookFailed(SCOPE, TARGET_MIUI_LONG_PRESS, t)
        }
    }

    /** Handles both the current `(SingleKeyGestureEvent, long)` and older `(long)` signatures. */
    private fun dispatchMiuiLongPress(args: Array<Any?>, rule: Any?): Boolean {
        val event = args.firstOrNull { it != null && it !is Long }
        if (event != null) return dispatchOnce(event, rule, "MIUI")

        val eventTime = args.lastOrNull() as? Long ?: return false
        return claimGesture(eventTime) && dispatchAction("MIUI", policyOf(rule), eventTime)
    }

    /**
     * The AOSP rule, reached when MIUI declines the gesture and forwards it through
     * `OriginalPowerKeyRuleBridge`. Hooking `onKeyGesture` rather than only `onLongPress` matters
     * because the assistant behavior (setting 5) leaves `onLongPress` for
     * `handleSingleKeyGestureInKeyGestureController`, which `powerLongPress` never sees.
     */
    private fun hookAospPowerKeyRuleGesture() {
        val ruleClass = AOSP_POWER_KEY_RULE_CLASS.toClassOrNull()
            ?: return DebugLog.hookSkipped(SCOPE, TARGET_AOSP_GESTURE, "class not found")
        runCatching {
            val method = ruleClass.declaredMethods.firstOrNull {
                it.name == "onKeyGesture" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name.endsWith("SingleKeyGestureEvent")
            } ?: return@runCatching DebugLog.hookSkipped(
                SCOPE, TARGET_AOSP_GESTURE, "method not found"
            )
            method.isAccessible = true
            deoptimize(method)
            method.hook("power_button_cts_rule_gesture") {
                before { param ->
                    if (!dispatchEnabled()) return@before
                    val event = param.args.firstOrNull() ?: return@before
                    // Only the long press is ours; press, multi-press and very-long-press must
                    // keep their original handling untouched.
                    if (readEventType(event) != GESTURE_TYPE_LONG_PRESS) return@before
                    if (dispatchOnce(event, param.thisObject, "AOSP")) {
                        param.result = null
                    }
                }
            }
        }.onFailure { t ->
            DebugLog.hookFailed(SCOPE, TARGET_AOSP_GESTURE, t)
        }
    }

    /** Last resort: the behavior switch itself, for a build where the rule classes are absent. */
    private fun hookAospPowerLongPress() {
        val policyClass = PHONE_WINDOW_MANAGER_CLASS.toClassOrNull()
            ?: return DebugLog.hookSkipped(SCOPE, TARGET_AOSP_LONG_PRESS, "class not found")
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
                    if (claimGesture(eventTime) &&
                        dispatchAction("AOSP", param.thisObject, eventTime)
                    ) {
                        // Skip the behavior switch (power menu / shutdown / assistant).
                        param.result = null
                    }
                }
            }
        }.onFailure { t ->
            DebugLog.hookFailed(SCOPE, TARGET_AOSP_LONG_PRESS, t)
        }
    }

    /** Claims the gesture the event belongs to and runs the action if this hook got it. */
    private fun dispatchOnce(event: Any, rule: Any?, layer: String): Boolean {
        val startTime = readEventStartTime(event)
        // Without a readable start time the press cannot be identified, so it is never treated as
        // already claimed; the action still runs with the platform's default time.
        if (startTime != null && !claimGesture(startTime)) return false
        return dispatchAction(layer, policyOf(rule), startTime ?: 0L)
    }

    /**
     * Reserves this press for the calling hook. The nested hooks for the same press then decline,
     * so a press runs the selected action exactly once no matter how many of the four hooks are
     * installed or whether the original body continues past a failed dispatch.
     */
    private fun claimGesture(startTime: Long): Boolean {
        val previous = claimedGestureStartTime.getAndSet(startTime)
        return previous != startTime
    }

    private fun dispatchEnabled(): Boolean =
        Preferences.powerButtonAction() != Preferences.POWER_BUTTON_ACTION_DISABLED

    /** `SingleKeyGestureEvent.getKeyCode()`; -1 when unavailable, which matches no real key. */
    private fun readEventKeyCode(event: Any): Int = runCatching {
        event.javaClass.getMethod("getKeyCode").invoke(event) as? Int ?: -1
    }.getOrDefault(-1)

    /** `SingleKeyGestureEvent.getType()`; -1 when unavailable, which never equals the long press. */
    private fun readEventType(event: Any): Int = runCatching {
        event.javaClass.getMethod("getType").invoke(event) as? Int ?: -1
    }.getOrDefault(-1)

    /**
     * `SingleKeyGestureEvent.getStartTime()`; falls back to the event time. `null` when neither
     * accessor exists, which disables — never misfires — the per-gesture claim.
     */
    private fun readEventStartTime(event: Any): Long? {
        for (name in listOf("getStartTime", "getEventTime")) {
            val value = runCatching {
                event.javaClass.getMethod(name).invoke(event) as? Long
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    /**
     * The `WindowManagerPolicy` behind a key rule, which is what the action needs
     * (`setPowerKeyHandled`, `launchAssistAction`, `performHapticFeedback`).
     *
     * MIUI's rule holds it as `mWindowManagerPolicy`; the AOSP rule is a non-static inner class of
     * `PhoneWindowManager` and so carries a synthetic `this$0`. `LocalServices` is the structural
     * fallback, and the correct answer when the object is already the policy itself.
     */
    private fun policyOf(rule: Any?): Any? {
        if (rule == null) return localWindowManagerPolicy()
        if (rule.javaClass.name == PHONE_WINDOW_MANAGER_CLASS) return rule
        for (fieldName in listOf("mWindowManagerPolicy", "this\$0")) {
            val value = runCatching {
                rule.javaClass.getDeclaredField(fieldName)
                    .apply { isAccessible = true }
                    .get(rule)
            }.getOrNull()
            if (value != null) return value
        }
        return localWindowManagerPolicy()
    }

    /** The live policy from system_server's own service registry. */
    private fun localWindowManagerPolicy(): Any? = runCatching {
        Class.forName("android.os.LocalServices")
            .getMethod("getService", Class::class.java)
            .invoke(null, Class.forName("com.android.server.policy.WindowManagerPolicy"))
    }.onFailure { t ->
        DebugLog.w(SCOPE, "failed to resolve the window manager policy", t)
    }.getOrNull()

    /**
     * Runs the selected long-press power action. The power key is always marked handled so the
     * key-up / multi-press bookkeeping still treats it as consumed by this long press; the haptic
     * plays only when the custom action actually fired, so a failed dispatch degrades to the
     * original system action without a phantom vibration.
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
        if (dispatched) {
            performHapticFeedback(policy)
        }
        return dispatched
    }

    /**
     * Launches the user's default digital assistant (默认助理, the app behind AOSP's
     * `android.app.role.ASSISTANT` — Google Assistant / Gemini etc., whatever 默认应用 selects).
     *
     * The platform path is preferred: `PhoneWindowManager.launchAssistAction(null, -2, eventTime,
     * 6)` is the exact call the AOSP "assistant" long-press (setting 5) makes, and it routes
     * through SystemUI's `AssistManager`, which creates a real assist session. Its method is
     * private on this baseline, hence the reflection up the class hierarchy (the runtime policy is
     * the MIUI subclass).
     *
     * That path is only correct while `AssistUtils.getAssistComponentForUser()` names something
     * `AssistManager` can launch — an activity, or the active voice-interaction service. HyperOS
     * leaves the selected assistant naming a *service* while the active voice service is XiaoAI, so
     * `AssistManager` renders an `ACTION_ASSIST` intent with a service component and the start
     * fails with `Activity class {...} does not exist`. [DefaultAssistantHooker] aligns that
     * setting; when it could not (or has not yet), the assistant is started here directly.
     */
    private fun launchDefaultAssistant(policy: Any?, eventTime: Long): Boolean {
        // The action can be changed from Circle to Search to Default assistant without restarting
        // system_server. Reconcile the platform's assistant/voice-service pair at the same point
        // where the action is consumed, so that change does not leave AssistManager on its stale
        // Google-activity branch.
        DefaultAssistantHooker.applyAlignment()
        val context = systemContext()
        val platformUsable = context == null || DefaultAssistantHooker.platformPathUsable(context)
        // Logged either way: which branch runs decides whether the assistant's real voice session
        // engages or its assist activity is merely opened, and that distinction is invisible from
        // the outside otherwise.
        DebugLog.i(
            SCOPE,
            "default assistant: platform assist path usable=$platformUsable"
        )
        if (platformUsable && policy != null) {
            val launched = runCatching {
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
            if (launched) return true
        }
        // Fallback: reach the selected assistant without AssistManager.
        if (context == null) return false
        return DefaultAssistantHooker.launchSelectedAssistant(
            context,
            eventTime,
            INVOCATION_TYPE_POWER_ASSIST
        )
    }

    /**
     * system_server's context. The policy carries one as `mContext`, and this process has no
     * Application, so `ActivityThread.getSystemContext()` is the second choice.
     */
    private fun systemContext(): Context? {
        systemContextCache?.let { return it }
        val resolved = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread")
                .invoke(null)
                ?.let { thread ->
                    Class.forName("android.app.ActivityThread")
                        .getMethod("getSystemContext")
                        .invoke(thread) as? Context
                }
        }.getOrNull()
        systemContextCache = resolved
        return resolved
    }

    /**
     * Plays the same power-long-press haptic the system uses for its own long-press actions
     * (`LONG_PRESS_POWER_BUTTON`), via the policy's private `performHapticFeedback(int, String)` so
     * the platform's own telemetry / vibrator routing applies.
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
}
