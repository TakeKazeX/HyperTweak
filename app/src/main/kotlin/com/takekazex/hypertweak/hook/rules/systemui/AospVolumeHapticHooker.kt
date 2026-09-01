package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Makes the AOSP volume panel vibrate like the stock MIUI panel instead of SystemUI's generic MSDL
 * "continuous" slider haptic.
 *
 * Root cause (OS4.0.0.21.XPMCNXM): `VolumeDialogControllerImpl.onVolumeChangedW` (SystemUI) computes
 * a haptic bitmask and dispatches `onPerformHapticFeedback(int flag)` once per volume change — the
 * same signal MIUI's volume plugin consumes via `VolumePanelViewController` ->
 * `VolumeUtil.performHapticFeedback` -> `miui.util.HapticFeedbackUtil.performHapticFeedback(
 * FLAG_MIUI_HAPTIC_*, false)` (a per-scene, device-tuned linear-motor waveform). The AOSP Compose
 * volume dialog (`com.android.systemui.volume.dialog.*`) never overrides `onPerformHapticFeedback`
 * (it is an empty default in the `Callbacks` interface), so that path is a no-op there. Instead its
 * slider always builds `Haptics.Enabled` with `SliderHapticFeedbackConfig(sliderStepSize = 0f)`,
 * routing through SystemUI's MSDL slider-haptics stack: `SliderHapticsViewModel.onValueChange` ->
 * `SliderStateTracker` -> `SliderHapticFeedbackProvider.onProgress(float)` ->
 * `MSDLPlayer.playToken(DRAG_INDICATOR_CONTINUOUS)`. A single volume-key press makes the slider
 * animate the handle to the new level over ~250 ms, emitting `onProgress` ~29 times — so the panel
 * plays the strong sustained `DRAG_INDICATOR_CONTINUOUS` waveform repeatedly (the "嗡嗡嗡").
 *
 * Fix: three hooks.
 *  - `VolumeDialogControllerImpl$C#onPerformHapticFeedback(int)`: the once-per-volume-change haptic
 *    intent. We play the MIUI `HapticFeedbackUtil` waveform there (MESH_NORMAL for an in-range
 *    change; skip at max/min so the boundary hook below supplies the "reached the top" cue). Because
 *    this maps 1:1 to a real volume step, one press = one tick, while holding/auto-repeat fires it
 *    per step = a following series.
 *  - `SliderHapticFeedbackProvider#onProgress(float)`: swallowed (short-circuited) so the
 *    animation-driven MSDL ramp no longer buzzes; the bookend flags are reset here to keep
 *    `executeOnBookend` (max/min) working.
 *  - `MSDLPlayerImpl#playToken(MSDLToken, InteractionProperties)`: when the ramp reaches its bookend
 *    and `executeOnBookend` plays `DRAG_THRESHOLD_INDICATOR_LIMIT`, we swallow it and play the MIUI
 *    boundary waveform (`FLAG_MIUI_HAPTIC_MESH_HEAVY` — the same effect the stock MIUI panel used at
 *    max) instead, so the "到顶" cue is also MIUI's.
 *
 * `SliderHapticFeedbackProvider`, `VolumeDialogControllerImpl$C`,
 * `com.google.android.msdl.domain.MSDLPlayerImpl` and `miui.util.HapticFeedbackUtil` are real,
 * unobfuscated names in the SystemUI/framework dex, so no DexKit is needed. Live behavior is gated
 * on `KEY_AOSP_VOLUME_PANEL && KEY_AOSP_VOLUME_HAPTIC_MIUI`, both snapshotted at `onHook` (the panel
 * switch already needs a SystemUI restart). Requires a SystemUI restart.
 */
object AospVolumeHapticHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "AospVolumeHaptic"

    private const val SLIDER_PROVIDER = "com.android.systemui.haptics.slider.SliderHapticFeedbackProvider"
    private const val CONTROLLER_CALLBACKS = "com.android.systemui.volume.VolumeDialogControllerImpl\$C"
    private const val MIUI_HAPTIC_UTIL = "miui.util.HapticFeedbackUtil"
    private const val MSDL_PLAYER_IMPL = "com.google.android.msdl.domain.MSDLPlayerImpl"
    private const val MSDL_TOKEN = "com.google.android.msdl.data.model.MSDLToken"

    // miui.view.MiuiHapticFeedbackConstants
    private const val FLAG_MIUI_HAPTIC_MESH_NORMAL = 268435461 // 0x10000001
    private const val FLAG_MIUI_HAPTIC_MESH_HEAVY = 268435460  // 0x10000004

    private const val TOKEN_DRAG_THRESHOLD_LIMIT = "DRAG_THRESHOLD_INDICATOR_LIMIT"

    @Volatile
    private var enabled = false

    // Cached reflection (hot path: every volume change).
    private var hapticUtil: Any? = null
    private var hapticUtilFailed = false
    private var performHapticFeedback: Method? = null
    private var providerInstance: Class<Any>? = null
    private var callbacksInstance: Class<Any>? = null
    private var fieldHasVibratedUpper: Field? = null
    private var fieldHasVibratedLower: Field? = null
    private var msdlPlayerClass: Class<Any>? = null
    private var thresholdToken: Any? = null

    override fun onPrepareHotReload() {
        enabled = false
        hapticUtil = null
        hapticUtilFailed = false
        performHapticFeedback = null
        providerInstance = null
        callbacksInstance = null
        fieldHasVibratedUpper = null
        fieldHasVibratedLower = null
        msdlPlayerClass = null
        thresholdToken = null
    }

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_AOSP_VOLUME_PANEL, false) ||
            !Preferences.getBoolean(Preferences.KEY_AOSP_VOLUME_HAPTIC_MIUI, true)
        ) {
            DebugLog.hookSkipped(TAG, "AOSP volume haptic", "disabled")
            return
        }
        enabled = true

        // 1) Play the MIUI waveform once per volume-level change, keyed off the haptic-intent signal.
        val callbacksClass = callbacksInstance
            ?: CONTROLLER_CALLBACKS.toClassOrNull()?.also { callbacksInstance = it }
            ?: run {
                DebugLog.hookSkipped(TAG, CONTROLLER_CALLBACKS, "class not found")
                return
            }
        val hapticIntent = callbacksClass.declaredMethods.firstOrNull {
            it.name == "onPerformHapticFeedback" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: run {
            DebugLog.hookSkipped(TAG, "$CONTROLLER_CALLBACKS#onPerformHapticFeedback(Int)", "method not found")
            return
        }
        deoptimize(hapticIntent)
        hapticIntent.hook {
            before { param ->
                if (!enabled) return@before
                val flag = param.args.getOrNull(0) as? Int ?: return@before
                val atMax = flag and 2 != 0
                val atMin = flag and 4 != 0
                if (!atMax && !atMin) {
                    playMiui(FLAG_MIUI_HAPTIC_MESH_NORMAL)
                }
            }
        }
        DebugLog.i(TAG, "HOOK_OK $CONTROLLER_CALLBACKS#onPerformHapticFeedback(Int)")

        // 2) Swallow the MSDL ramp so the animation-driven buzz is gone; reset bookend flags so the
        //    max/min limit haptic (executeOnBookend) keeps working.
        val clazz = providerInstance
            ?: SLIDER_PROVIDER.toClassOrNull()?.also { providerInstance = it }
            ?: run {
                DebugLog.hookSkipped(TAG, SLIDER_PROVIDER, "class not found")
                return
            }
        val onProgress = clazz.declaredMethods.firstOrNull {
            it.name == "onProgress" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType
        } ?: run {
            DebugLog.hookSkipped(TAG, "$SLIDER_PROVIDER#onProgress(Float)", "method not found")
            return
        }
        fieldHasVibratedUpper = clazz.getDeclaredField("hasVibratedAtUpperBookend").apply { isAccessible = true }
        fieldHasVibratedLower = clazz.getDeclaredField("hasVibratedAtLowerBookend").apply { isAccessible = true }
        deoptimize(onProgress)
        onProgress.hook {
            before { param ->
                if (!enabled) return@before
                // Swallow the MSDL ramp and reset the bookend flags so executeOnBookend (max/min
                // limit haptic) still fires as the original onProgress would have.
                val provider = param.thisObject
                resetBookendFlags(provider)
                param.result = null
            }
        }
        DebugLog.i(TAG, "HOOK_OK $SLIDER_PROVIDER#onProgress(Float)")

        // 3) Route the max/min "limit" MSDL token (played by executeOnBookend for the bookend of the
        //    ramp) to the MIUI boundary waveform instead of the generic DRAG_THRESHOLD_INDICATOR_LIMIT.
        val playerClass = msdlPlayerClass
            ?: MSDL_PLAYER_IMPL.toClassOrNull()?.also { msdlPlayerClass = it }
            ?: run {
                DebugLog.hookSkipped(TAG, MSDL_PLAYER_IMPL, "class not found")
                return
            }
        val playToken = playerClass.declaredMethods.firstOrNull {
            it.name == "playToken" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0].name == MSDL_TOKEN
        } ?: run {
            DebugLog.hookSkipped(TAG, "$MSDL_PLAYER_IMPL#playToken(MSDLToken,InteractionProperties)", "method not found")
            return
        }
        if (thresholdToken == null) {
            val tokenClass = MSDL_TOKEN.toClassOrNull()
            thresholdToken = tokenClass?.getField(TOKEN_DRAG_THRESHOLD_LIMIT)?.get(null)
        }
        val threshold = thresholdToken
        deoptimize(playToken)
        playToken.hook {
            before { param ->
                if (!enabled || threshold == null) return@before
                if (param.args.getOrNull(0) === threshold) {
                    param.result = null
                    playMiui(FLAG_MIUI_HAPTIC_MESH_HEAVY)
                }
            }
        }
        DebugLog.i(TAG, "HOOK_OK $MSDL_PLAYER_IMPL#playToken(MSDLToken,InteractionProperties)")
    }

    private fun resetBookendFlags(provider: Any?) {
        if (provider == null) return
        runCatching {
            fieldHasVibratedUpper?.setBoolean(provider, false)
            fieldHasVibratedLower?.setBoolean(provider, false)
        }.onFailure { t ->
            DebugLog.w(TAG, "resetBookendFlags failed", t)
        }
    }

    private fun playMiui(effectId: Int) {
        runCatching {
            val util = hapticUtil ?: resolveHapticUtil() ?: return
            performHapticFeedback?.invoke(util, effectId, false)
        }.onFailure { t ->
            DebugLog.w(TAG, "miui HapticFeedbackUtil.play failed", t)
        }
    }

    private fun resolveHapticUtil(): Any? {
        if (hapticUtilFailed) return null
        return runCatching {
            val cls = MIUI_HAPTIC_UTIL.toClassOrNull() ?: run {
                hapticUtilFailed = true
                DebugLog.hookSkipped(TAG, MIUI_HAPTIC_UTIL, "class not found")
                return null
            }
            val ctx = currentApplication() ?: run {
                hapticUtilFailed = true
                DebugLog.hookSkipped(TAG, MIUI_HAPTIC_UTIL, "no application context")
                return null
            }
            val ctor = cls.getDeclaredConstructor(android.content.Context::class.java, Boolean::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            val method = cls.getDeclaredMethod(
                "performHapticFeedback",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val util = ctor.newInstance(ctx, false)
            performHapticFeedback = method
            hapticUtil = util
            util
        }.onFailure { t ->
            hapticUtilFailed = true
            DebugLog.hookFailed(TAG, MIUI_HAPTIC_UTIL, t)
        }.getOrNull()
    }

    private fun currentApplication(): Any? {
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication").apply { isAccessible = true }
            m.invoke(null) as? android.content.Context
        }.getOrNull()
    }
}
