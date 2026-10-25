package com.takekazex.hypertweak.hook.rules.trustservice

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * Disables the MiTrustService MRM risk-monitoring initialization gate.
 *
 * The target is resolved from the two strings used by the shipped
 * `MiTrustService/statusEventHandle` path instead of relying on its obfuscated
 * class or method name. A missing or ambiguous match leaves the native path
 * untouched.
 */
object MiTrustRiskMonitoringHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "MiTrustRiskMonitoring"
    private const val PACKAGE = "com.xiaomi.trustservice"
    private const val STATUS_EVENT_TAG = "MiTrustService/statusEventHandle"
    private const val MRM_SERVICE = "vendor.xiaomi.hardware.mrm.IMrm/default"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING, false)) {
            DebugLog.hookSkippedDebug(TAG, "MiTrustService MRM gate", "disabled")
            return
        }

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "MiTrustService MRM gate", "source APK unavailable")
            return
        }
        val method = resolveDexContract("mitrust.mrm_gate", apkPath, compute = compute@ { bridge ->
            val methodData = bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType("boolean")
                    addUsingString(STATUS_EVENT_TAG, StringMatchType.Equals)
                    addUsingString(MRM_SERVICE, StringMatchType.Equals)
                }
            }.singleOrNull() ?: return@compute null
            runCatching { methodData.getMethodInstance(classLoader) }
                .onFailure { DebugLog.hookFailed(TAG, "${methodData.className}#${methodData.methodName}", it) }
                .getOrNull()

        }, encode = { target -> DexContractMembers.encode("gate" to target) }, decode = { members -> members.method("gate").also { require(it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType) } }) ?: run {
            DebugLog.hookSkipped(TAG, "MiTrustService MRM gate", "DexKit match missing or ambiguous")
            return
        }

        if (method.parameterCount != 0 || method.returnType != Boolean::class.javaPrimitiveType) {
            DebugLog.hookSkipped(TAG, method.toGenericString(), "resolved method shape is not ()Z")
            return
        }

        runCatching {
            method.isAccessible = true
            deoptimize(method)
            method.hook("mitrust_disable_risk_monitoring") {
                before { param ->
                    // Returning false before the native lookup prevents MRM from being
                    // initialized while leaving every caller and callback path intact.
                    HookFailurePolicy.open(TAG, "statusEventHandle.before", Unit) {
                        param.result = false
                    }
                }
            }
        }.onFailure { t ->
            DebugLog.hookFailed(TAG, method.toGenericString(), t)
        }.onSuccess {
            DebugLog.i(TAG, "MRM risk-monitoring gate disabled on ${method.toGenericString()}")
        }
    }
}
