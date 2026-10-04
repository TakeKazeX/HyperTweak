package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
 * value — mode 1 always samples, mode 2 never does — while leaving the flow intact. Hot reload replays the native
 * cold flow once to refresh the already-running helper.
 */
object RegionSamplingHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val TRANSFORM_CACHE_KEY = "statusBarRegionSamplingTransform"
    private const val TRANSFORM_OWNER_MARKER =
        "com.miui.systemui.statusbar.core.StatusBarRegionSamplingInteractor\$regionSampling"

    private var replay: HostFlowCollector.Handle? = null
    @Volatile private var retiring = false

    override fun onPrepareHotReload() {
        retiring = true
        replay?.cancel()
        replay = null
    }

    internal fun recover(component: Any) {
        val host = StatusIconHostAccess.provider(component, "statusBarRegionSamplingInteractorProvider") ?: return
        val scope = StatusIconHostAccess.read(host, "scope") ?: return
        val flow = StatusIconHostAccess.read(host, "regionSampling") ?: return
        val helper = StatusIconHostAccess.read(host, "regionSamplingHelper") ?: return
        var delivered = false
        replay = HostFlowCollector.collect(scope, flow, isCurrent = { !retiring && !delivered }, consumer = { value ->
            val enabled = value as? Boolean ?: return@collect
            delivered = true
            if (enabled) {
                val bounds = StatusIconHostAccess.read(host, "samplingBounds") as? Rect ?: return@collect
                StatusIconHostAccess.method(helper, "start", Rect::class.java)?.invoke(helper, bounds)
            } else {
                val handler = StatusIconHostAccess.read(host, "resampleHandler") as? Handler
                val task = StatusIconHostAccess.read(host, "resampleRunnable") as? Runnable
                if (task != null) handler?.removeCallbacks(task)
                StatusIconHostAccess.invoke(helper, "stop")
            }
            // Cancellation after assignment also handles an immediately delivered first value.
            Handler(Looper.getMainLooper()).post { replay?.cancel(); replay = null }
        })
    }

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
            DebugLog.hookSkipped(TAG, "RegionSampling transform", "Function3 invoke method not found")
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
