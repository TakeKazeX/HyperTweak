package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Forced status-bar region sampling, ported from Hyper Helper's `RegionSampling`
 * (OS4_ADAPTATION_PLAN.md T6).
 *
 * OS3 drove this through `LightBarControllerImplInjector.useRegionSampling`; on OS4 the sampling
 * gate moved to `StatusBarRegionSamplingInteractor.regionSampling`, a combine flow of the status
 * bar state and `MiuiConfigurationRepositoryImpl.isNightMode` whose collector starts/stops the
 * `RegionSamplingHelper`. The flow field is typed as the concrete inlined-combine class (verified
 * in smali), so replacing it with a `StateFlow` would fail the collector's check-cast and crash
 * the coroutine. Instead the hook forces the transform lambda
 * `StatusBarRegionSamplingInteractor$regionSampling$1.invoke` (the `Function3` bridge the combine
 * machinery calls) to always emit the requested value — mode 1 always samples, mode 2 never does —
 * leaving the flow itself intact. Requires a SystemUI restart.
 */
object RegionSamplingHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val TRANSFORM_CLASS =
        "com.miui.systemui.statusbar.core.StatusBarRegionSamplingInteractor\$regionSampling\$1"

    override fun onHook() {
        val mode = Preferences.getInt(Preferences.KEY_STATUSBAR_REGION_SAMPLING, 0)
        if (mode !in 1..2) {
            DebugLog.hookSkipped(TAG, "RegionSampling", "mode $mode not active")
            return
        }
        val transformClass = TRANSFORM_CLASS.toClassOrNull()
        if (transformClass == null) {
            DebugLog.hookSkipped(TAG, TRANSFORM_CLASS, "class not found")
            return
        }
        val invoke = transformClass.findMethodOrNull { name("invoke"); paramCount(3) }
        if (invoke == null) {
            DebugLog.hookSkipped(TAG, "$TRANSFORM_CLASS#invoke", "method not found")
            return
        }
        deoptimize(invoke)
        val forced = mode == 1
        invoke.hook {
            before { param -> param.result = forced }
        }
        DebugLog.i(TAG, "RegionSampling installed: mode=$mode forced=$forced")
    }
}