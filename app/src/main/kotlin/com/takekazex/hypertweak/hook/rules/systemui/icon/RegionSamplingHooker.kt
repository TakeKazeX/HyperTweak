package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File

/**
 * Forced status-bar region sampling, ported from Hyper Helper's `RegionSampling`
 * (OS4_ADAPTATION_PLAN.md T6).
 *
 * OS3 drove this through `LightBarControllerImplInjector.useRegionSampling`; on OS4 the sampling
 * gate moved to `StatusBarRegionSamplingInteractor.regionSampling`, a combine flow of the status
 * bar state and `MiuiConfigurationRepositoryImpl.isNightMode` whose collector starts/stops the
 * `RegionSamplingHelper`. The flow field is typed as the concrete inlined-combine class (verified
 * in smali), so replacing it with a `StateFlow` would fail the collector's check-cast and crash
 * the coroutine. DexKit locates the transform owner by the flow property's semantic marker and
 * validates its `Function3` bridge shape. The hook forces that transform to emit the requested
 * value — mode 1 always samples, mode 2 never does — while leaving the flow intact. Requires a
 * SystemUI restart.
 */
object RegionSamplingHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val TRANSFORM_CACHE_KEY = "statusBarRegionSamplingTransform"
    private const val TRANSFORM_OWNER_MARKER =
        "com.miui.systemui.statusbar.core.StatusBarRegionSamplingInteractor\$regionSampling"

    override fun onHook() {
        val mode = Preferences.getInt(Preferences.KEY_STATUSBAR_REGION_SAMPLING, 0)
        if (mode !in 1..2) {
            DebugLog.hookSkippedDebug(TAG, "RegionSampling", "mode $mode not active")
            return
        }
        val transformClass = resolveTransformClass()
        if (transformClass == null) {
            DebugLog.hookSkipped(TAG, "RegionSampling transform", "unique DexKit target not found")
            return
        }
        val invoke = transformClass.findMethodOrNull { name("invoke"); paramCount(3) }
        if (invoke == null) {
            DebugLog.hookSkipped(TAG, "RegionSampling transform", "invoke(FlowCollector,Object,Continuation) not found")
            return
        }
        deoptimize(invoke)
        val forced = mode == 1
        invoke.hook {
            before { param -> param.result = forced }
        }
        DebugLog.i(TAG, "RegionSampling installed: mode=$mode forced=$forced")
    }

    private fun resolveTransformClass(): Class<*>? {
        val appInfo = hookParam.appInfo ?: return null
        val apkPath = appInfo.sourceDir ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        return DexKitManager.resolveClasses(
            cacheDir = File(baseDir, "cache"),
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                TRANSFORM_CACHE_KEY to { bridge ->
                    bridge.findClass {
                        matcher {
                            className(TRANSFORM_OWNER_MARKER, StringMatchType.Contains)
                            methods {
                                matchType(MatchType.Contains)
                                countMin(1)
                                add {
                                    name("invoke")
                                    paramCount(3)
                                    returnType(Any::class.java)
                                }
                            }
                        }
                    }.toList().singleOrNull { data ->
                        runCatching {
                            data.getInstance(classLoader).declaredMethods.any {
                                it.name == "invoke" && it.parameterCount == 3 &&
                                    it.returnType == Any::class.java
                            }
                        }.getOrDefault(false)
                    }?.name
                }
            ),
            validators = mapOf(
                TRANSFORM_CACHE_KEY to { type ->
                    type.name.contains(TRANSFORM_OWNER_MARKER) &&
                        type.declaredMethods.count {
                            it.name == "invoke" && it.parameterCount == 3 &&
                                it.returnType == Any::class.java
                        } == 1
                }
            )
        )[TRANSFORM_CACHE_KEY]
    }
}
