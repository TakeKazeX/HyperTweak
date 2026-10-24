package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.DexSession
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object DetailedPowerDataFeature : DexFeature {
    override val id = "securitycenter.detailed_power"
    override val schema = 1
}

/**
 * Opens the stock screen-use/detail path used by Security Center's power ranking page.
 *
 * MeowCeiler identifies the gates by the strings in the current obfuscated class: the method
 * containing `ishtar/nuwa/fuxi` is the screen-power split gate, while the class containing
 * `not support screenPowerSplit` and `PowerRankHelperHolder` owns the companion boolean gates.
 * Keeping that resolution here makes the hook survive method-name changes while still requiring
 * the verified current signatures: static, no-argument boolean methods only.
 *
 * Resolution is a cached descriptor set rather than a fresh scan per process. It used to run as an
 * uncached `DexRuntime.query`, which every Security Center process repeated on the main thread -
 * five scans per boot, 573-1301 ms each (measured 2026-10-24).
 */
object DetailedPowerDataHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "DetailedPowerData"
    private const val PACKAGE = "com.miui.securitycenter"
    internal const val SCREEN_POWER_SPLIT_ERROR = "not support screenPowerSplit"
    internal const val POWER_RANK_HELPER_HOLDER = "PowerRankHelperHolder"

    /** Display-only page content: off means not installed, and the cache makes enabling free. */
    override val installPolicy: InstallPolicy = InstallPolicy.WHEN_ENABLED

    override fun isFeatureEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA, false)

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!isFeatureEnabled()) {
            DebugLog.hookSkippedDebug(TAG, "detailed power data", "disabled")
            return
        }
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "detailed power data", "source APK unavailable")
            return
        }
        prepareDexTargets(
            name = DetailedPowerDataFeature.id,
            resolve = { resolve(apkPath) },
            install = ::install,
        )
    }

    private fun resolve(apkPath: String): ResolvedTargets? = DexRuntime.resolve(
        feature = DetailedPowerDataFeature,
        apkPath = apkPath,
        cacheDir = dexCacheDirectory,
        cacheEmpty = true,
        appInfo = hookParam.appInfo,
        compute = DetailedPowerDataCache::descriptors,
        decode = { stored, _ -> DetailedPowerDataCache.decode(stored, classLoader) },
    )

    private fun install(targets: ResolvedTargets) {
        val gates = targets.companionGates
            .plus(targets.screenPowerSplitGate)
            .distinctBy(Method::toGenericString)

        var installed = 0
        gates.forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook("detailed_power_data_" + method.toGenericString()) {
                    returnConstant(method == targets.screenPowerSplitGate)
                }
                installed++
            }.onFailure {
                DebugLog.hookFailed(TAG, method.toGenericString(), it)
            }
        }

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "detailed power data", "hook registration failed")
        } else {
            DebugLog.i(TAG, "detailed power data gates opened boundaries=" + installed)
        }
    }

    internal fun isStaticBooleanGate(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType

    internal data class ResolvedTargets(
        val screenPowerSplitGate: Method,
        val companionGates: List<Method>,
    )
}

/** Descriptor compute/decode for persistent target caching. */
internal object DetailedPowerDataCache {
    private const val SPLIT_KEY = "gate.split"
    private const val COMPANION_PREFIX = "gate.companion."

    fun descriptors(session: DexSession): Map<String, String> = session.dexKit { bridge ->
        val splitCandidates = bridge.findMethod {
            matcher {
                paramCount(0)
                returnType("boolean")
                addUsingString("ishtar", StringMatchType.Equals)
                addUsingString("nuwa", StringMatchType.Equals)
                addUsingString("fuxi", StringMatchType.Equals)
            }
        }.toList()
        val split = splitCandidates
            .filter { it.isMethod && Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "boolean" }
            .map { it.descriptor }
            .distinct()
            .singleOrNull() ?: return@dexKit emptyMap()

        val holderCandidates = bridge.findClass {
            matcher {
                usingEqStrings(
                    DetailedPowerDataHooker.SCREEN_POWER_SPLIT_ERROR,
                    DetailedPowerDataHooker.POWER_RANK_HELPER_HOLDER,
                )
            }
        }.toList()
        val holder = holderCandidates.singleOrNull()?.name ?: return@dexKit emptyMap()

        buildMap {
            put(SPLIT_KEY, split)
            // The companion gates are ordinary static boolean methods on the holder class. They are
            // recorded by name so the decoder can re-validate them against the live class instead
            // of trusting a descriptor that may no longer match the installed build.
            put(COMPANION_PREFIX + "owner", holder)
        }
    } ?: emptyMap()

    fun decode(stored: Map<String, String>, loader: ClassLoader): DetailedPowerDataHooker.ResolvedTargets? {
        val splitDescriptor = stored[SPLIT_KEY] ?: return null
        val split = MethodDescriptorCodec.method(splitDescriptor, loader) ?: return null
        if (!DetailedPowerDataHooker.isStaticBooleanGate(split)) return null

        val holderName = stored[COMPANION_PREFIX + "owner"] ?: return null
        // Class names are obfuscated and change between builds; a stale name must invalidate the
        // entry rather than install nothing silently.
        val holder = runCatching {
            Class.forName(holderName, false, loader)
        }.getOrNull() ?: return null
        val companions = holder.declaredMethods
            .filter(DetailedPowerDataHooker::isStaticBooleanGate)
        return DetailedPowerDataHooker.ResolvedTargets(split, companions)
    }
}
