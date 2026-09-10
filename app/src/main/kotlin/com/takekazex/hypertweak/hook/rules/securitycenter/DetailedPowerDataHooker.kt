package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Opens the stock screen-use/detail path used by Security Center's power ranking page.
 *
 * MeowCeiler identifies the gates by the strings in the current obfuscated class: the method
 * containing `ishtar/nuwa/fuxi` is the screen-power split gate, while the class containing
 * `not support screenPowerSplit` and `PowerRankHelperHolder` owns the companion boolean gates.
 * Keeping that resolution here makes the hook survive method-name changes while still requiring
 * the verified current signatures: static, no-argument boolean methods only.
 */
object DetailedPowerDataHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "DetailedPowerData"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val SCREEN_POWER_SPLIT_ERROR = "not support screenPowerSplit"
    private const val POWER_RANK_HELPER_HOLDER = "PowerRankHelperHolder"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA, false)) {
            DebugLog.hookSkipped(TAG, "detailed power data", "disabled")
            return
        }

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "detailed power data", "source APK unavailable")
            return
        }
        val targets = resolveTargets(apkPath) ?: run {
            DebugLog.hookSkipped(TAG, "detailed power data", "validated gates not found")
            return
        }
        val gates = targets.companionGates
            .plus(targets.screenPowerSplitGate)
            .distinctBy(Method::toGenericString)

        var installed = 0
        gates.forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook("detailed_power_data_${method.toGenericString()}") {
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
            DebugLog.i(TAG, "detailed power data gates opened boundaries=$installed")
        }
    }

    private fun resolveTargets(apkPath: String): ResolvedTargets? {
        val dexTargets = DexKitManager.withBridge(apkPath) { bridge ->
            val splitCandidates = bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType("boolean")
                    addUsingString("ishtar", StringMatchType.Equals)
                    addUsingString("nuwa", StringMatchType.Equals)
                    addUsingString("fuxi", StringMatchType.Equals)
                }
            }.toList()
            val splitGate = materializeUnique(splitCandidates)

            val holderCandidates = bridge.findClass {
                matcher {
                    usingEqStrings(SCREEN_POWER_SPLIT_ERROR, POWER_RANK_HELPER_HOLDER)
                }
            }.toList()
            val holder = holderCandidates.singleOrNull()?.let { data ->
                runCatching { data.getInstance(classLoader) }
                    .onFailure { t -> DebugLog.w(TAG, "failed to load power-rank holder ${data.name}", t) }
                    .getOrNull()
            }
            val companionGates = holder?.declaredMethods
                ?.filter(::isStaticBooleanGate)
                .orEmpty()
            if (splitGate == null) null else ResolvedTargets(splitGate, companionGates)
        }
        if (dexTargets != null) return dexTargets

        // The current 13.2.7 baseline is `legacypowerrank.f`; retain a narrow fallback when the
        // native resolver is unavailable, but still require the exact method signatures.
        val holder = "com.miui.powercenter.legacypowerrank.f".toClassOrNull() ?: return null
        val splitGate = holder.declaredMethods.singleOrNull {
            it.name == "o" && isStaticBooleanGate(it)
        } ?: return null
        return ResolvedTargets(
            screenPowerSplitGate = splitGate,
            companionGates = holder.declaredMethods.filter(::isStaticBooleanGate)
        )
    }

    private fun materializeUnique(candidates: List<org.luckypray.dexkit.result.MethodData>): Method? {
        val methods = candidates.mapNotNull { data ->
            runCatching { data.getMethodInstance(classLoader) }
                .onFailure { t -> DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", t) }
                .getOrNull()
                ?.takeIf(::isStaticBooleanGate)
        }
        return methods.singleOrNull()
    }

    private fun isStaticBooleanGate(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType

    private data class ResolvedTargets(
        val screenPowerSplitGate: Method,
        val companionGates: List<Method>
    )
}
