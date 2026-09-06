package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Modifier

/**
 * Reveals the 主动视觉感知 (AON visual perception) controls on devices whose `config_aon_*`
 * resource gates are false.
 *
 * Only runs in `com.android.settings` (the gates live in `com.android.settings.MiuiUtils`;
 * see docs/FEATURE_DETAIL.md). Forcing the Settings-side capability checks reveals the hidden
 * preference rows — 感知锁屏/靠近亮屏 (screen on/off), 非注视感知 (anti burn-in), 隔空手势 (air
 * gestures). It does NOT touch the runtime sensor gates in system_server, so a revealed row is
 * only effective when the device actually supports the mode.
 */
object VisualPerceptionSettingsHooker : StaticHooker() {
    private const val TAG = "VisualPerception"
    private const val CLASS = "com.android.settings.MiuiUtils"

    /** Checks gated by the 解锁更多主动视觉感知 preference. */
    private val visualPerceptionGates = setOf(
        "isSupportAonScreenOn",
        "isSupportAonScreenOff",
        "isSupportAonAntiBurn",
        "isAonAvailable"
    )

    /** Checks gated by the 解锁更多隔空手势 preference. */
    private val aonGestureGates = setOf("isSupportAonGesture")

    override fun onHook() {
        val clazz = CLASS.toClassOrNull() ?: return
        val names = visualPerceptionGates + aonGestureGates
        var count = 0
        clazz.declaredMethods.filter { method ->
            method.name in names && Modifier.isStatic(method.modifiers) &&
                method.returnType == java.lang.Boolean.TYPE &&
                (method.parameterTypes.isEmpty() ||
                    method.parameterTypes.contentEquals(arrayOf(android.content.Context::class.java)))
        }.forEach { method ->
            deoptimize(method)
            method.hook("visual_perception_${method.name}") { after { param ->
                val unlockVisual = Preferences.unlockMoreVisualPerception()
                val unlockGestures = Preferences.unlockMoreAonGestures()
                if ((method.name in visualPerceptionGates && unlockVisual) ||
                    (method.name in aonGestureGates && (unlockGestures || unlockVisual))
                ) {
                    param.result = true
                }
            } }
            count++
        }
        DebugLog.i(TAG, "forced $count AON capability checks in Settings")
    }
}
