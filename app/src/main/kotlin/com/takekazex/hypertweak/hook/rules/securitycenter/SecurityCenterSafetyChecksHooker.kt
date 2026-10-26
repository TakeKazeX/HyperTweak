package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

private object SecurityCenterSafetyFeature : DexFeature {
    override val id = "securitycenter.safety_checks"
    override val schema = 4
}

private const val TAG = "SecurityCenterSafetyChecks"
private const val PACKAGE = "com.miui.securitycenter"
private const val SAFE_PAY_KEY = "key_safepay_auto_scan_state"
private const val ROOT_SCAN_KEY = "key_check_item_root"
private const val USB_INTERCEPT_KEY = "permcenter_install_intercept_enabled"
private val PROCESS_NAMES = setOf(PACKAGE, "$PACKAGE.remote", "$PACKAGE.cache")
private val ROOT_MARKERS = setOf("/system/bin/", "/system/xbin/", "/system/sbin/", "/sbin/", "/vendor/bin/", "su")
private val ROOT_LOOP_OPS = setOf("filled-new-array", "aget-object", "add-int/lit8")

/** Facts retained from a DEX query so the local-su family is selected by its loop behavior. */
internal data class LocalSuPredicateEvidence(
    val descriptor: String,
    val parameterCount: Int,
    val returnTypeName: String,
    val hasFileExists: Boolean,
    val strings: Set<String>,
    val opNames: Set<String>,
)

/**
 * Resolve every independent local-su path loop. The result may vary by OTA: adding a fifth
 * structurally equivalent scan or removing one scan does not change the acceptance rule.
 */
internal object LocalSuPredicateFamilyResolver {
    fun select(candidates: List<LocalSuPredicateEvidence>): List<LocalSuPredicateEvidence> = candidates
        .filter { evidence ->
            evidence.parameterCount == 0 && evidence.returnTypeName == "boolean" &&
                evidence.hasFileExists && evidence.strings.containsAll(ROOT_MARKERS) &&
                evidence.opNames.containsAll(ROOT_LOOP_OPS) &&
                evidence.opNames.any { it.startsWith("if-") } && evidence.opNames.any { it.startsWith("goto") }
        }
        .distinctBy { it.descriptor }
}

private data class GateTarget(val method: Method, val callers: List<Method>)

private data class SecurityCenterSafetyTargets(
    val localSuPredicates: List<GateTarget>,
    val safePayGetter: GateTarget?,
    val rootScanGetter: GateTarget?,
    val usbInterceptGetter: GateTarget?,
)

/** Independent live gates for Security Center's local checks and USB-install configuration. */
object SecurityCenterSafetyChecksHooker : DynamicHooker() {
    override fun onHook() {
        if (hookParam.packageName != PACKAGE || hookParam.processName !in PROCESS_NAMES) return
        val apkPath = hookParam.appInfo?.sourceDir?.takeIf(String::isNotBlank) ?: run {
            DebugLog.hookSkipped(TAG, "Security Center checks", "source APK unavailable")
            return
        }
        prepareDexTargets(
            name = SecurityCenterSafetyFeature.id,
            resolve = { resolve(apkPath) },
            install = { targets -> install(targets) },
        )
    }

    private fun resolve(apkPath: String): SecurityCenterSafetyTargets? = DexRuntime.resolve(
        feature = SecurityCenterSafetyFeature,
        apkPath = apkPath,
        cacheDir = dexCacheDirectory,
        cacheEmpty = true,
            compute = { session -> session.dexKit { bridge -> resolveDescriptors(bridge, apkPath) } ?: emptyMap() },
        decode = { values, _ -> decodeTargets(values) },
    )

    private fun resolveDescriptors(bridge: DexKitBridge, apkPath: String): Map<String, String> = buildMap {
        val index = DexRuntime.operands(apkPath)
        val localCandidates = runCatching {
            bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType("boolean")
                    ROOT_MARKERS.forEach { addUsingString(it, StringMatchType.Equals) }
                }
            }.filter(::isLocalSuPredicate).distinctBy { it.descriptor }
        }.onFailure { DebugLog.w(TAG, "local su predicate query failed", it) }.getOrDefault(emptyList())

        val evidence = localCandidates.map { candidate ->
            LocalSuPredicateEvidence(
                descriptor = candidate.descriptor,
                parameterCount = candidate.paramCount,
                returnTypeName = candidate.returnTypeName,
                hasFileExists = candidate.invokes.any { it.descriptor == "Ljava/io/File;->exists()Z" },
                strings = candidate.usingStrings.toSet(),
                opNames = candidate.opNames.toSet(),
            )
        }
        val selectedSu = LocalSuPredicateFamilyResolver.select(evidence)
        put("local_su.count", selectedSu.size.toString())
        selectedSu.forEachIndexed { index, target ->
            val methodData = localCandidates.singleOrNull { it.descriptor == target.descriptor } ?: return@forEachIndexed
            val callers = methodData.callers.filter { it.isMethod }.distinctBy { it.descriptor }
            put("local_su.$index.method", target.descriptor)
            put("local_su.$index.callers.count", callers.size.toString())
            callers.forEachIndexed { callerIndex, caller -> put("local_su.$index.caller.$callerIndex", caller.descriptor) }
        }
        if (selectedSu.size != localCandidates.size) {
            DebugLog.w(TAG, "local su structural candidates=${localCandidates.size}, family matches=${selectedSu.size}; unmatched methods are ignored")
        }

        putTarget("safepay", preferenceGetter(bridge, index, SAFE_PAY_KEY), SAFE_PAY_KEY)
        putTarget("root_scan", preferenceGetter(bridge, index, ROOT_SCAN_KEY), ROOT_SCAN_KEY)
        putTarget("usb_intercept", preferenceGetter(bridge, index, USB_INTERCEPT_KEY)?.takeIf(::hasAdbInstallActivityCaller), USB_INTERCEPT_KEY)
    }

    private fun preferenceGetter(bridge: DexKitBridge, index: DexOperandIndex, key: String): MethodData? = runCatching {
        bridge.findMethod {
            matcher {
                paramCount(0)
                returnType("boolean")
                addUsingString(key, StringMatchType.Equals)
            }
        }.filter { data ->
            data.isMethod && data.paramCount == 0 && data.returnTypeName == "boolean" &&
                key in data.usingStrings && data.invokes.any { invoke ->
                    invoke.isMethod && invoke.paramTypeNames == listOf("java.lang.String", "boolean") &&
                        invoke.returnTypeName == "boolean"
                } && index.code(data.descriptor).calls.count { call ->
                    SecurityPreferenceReadContract.matches(call, key)
                } == 1
        }.distinctBy { it.descriptor }.singleOrNull()
    }.onFailure { DebugLog.w(TAG, "preference getter query failed key=$key", it) }.getOrNull()

    private fun hasAdbInstallActivityCaller(method: MethodData): Boolean = runCatching {
        method.callers.any {
            it.className == "com.miui.permcenter.install.AdbInstallActivity" &&
                it.methodName == "onActivityCreated" && it.paramCount == 0 && it.returnTypeName == "void"
        }
    }.getOrDefault(false)

    private fun MutableMap<String, String>.putTarget(prefix: String, target: MethodData?, role: String = prefix) {
        if (target == null) {
            DebugLog.w(TAG, "target=$role missing or ambiguous; skipping this subfeature")
            return
        }
        val callers = target.callers.filter { it.isMethod }.distinctBy { it.descriptor }
        put("$prefix.method", target.descriptor)
        put("$prefix.callers.count", callers.size.toString())
        callers.forEachIndexed { index, caller -> put("$prefix.caller.$index", caller.descriptor) }
    }

    private fun isLocalSuPredicate(method: MethodData): Boolean = runCatching {
        method.isMethod && method.paramCount == 0 && method.returnTypeName == "boolean" &&
            method.invokes.any { it.descriptor == "Ljava/io/File;->exists()Z" } &&
            method.usingStrings.containsAll(ROOT_MARKERS) && method.opNames.containsAll(ROOT_LOOP_OPS) &&
            method.opNames.any { it.startsWith("if-") } && method.opNames.any { it.startsWith("goto") }
    }.getOrDefault(false)

    private fun decodeTargets(values: Map<String, String>): SecurityCenterSafetyTargets? {
        fun target(prefix: String): GateTarget? {
            val methodDescriptor = values["$prefix.method"] ?: return null
            val method = MethodDescriptorCodec.method(methodDescriptor, classLoader) ?: return null
            if (method.parameterCount != 0 || method.returnType != Boolean::class.javaPrimitiveType) return null
            val count = values["$prefix.callers.count"]?.toIntOrNull()?.takeIf { it in 0..512 } ?: return null
            val callers = (0 until count).map { index ->
                val descriptor = values["$prefix.caller.$index"] ?: return null
                MethodDescriptorCodec.method(descriptor, classLoader) ?: return null
            }
            return GateTarget(method, callers)
        }

        val suCount = values["local_su.count"]?.toIntOrNull()?.takeIf { it in 0..128 } ?: return null
        val suTargets = ArrayList<GateTarget>(suCount)
        var suCacheFailed = false
        repeat(suCount) { index ->
            val descriptor = values["local_su.$index.method"] ?: run { suCacheFailed = true; return@repeat }
            val method = MethodDescriptorCodec.method(descriptor, classLoader)
            val callerCount = values["local_su.$index.callers.count"]?.toIntOrNull()?.takeIf { it in 0..512 }
            var callerDecodeFailed = false
            val callers: List<Method>? = if (callerCount == null) null else buildList<Method> {
                repeat(callerCount) { callerIndex ->
                    val callerDescriptor = values["local_su.$index.caller.$callerIndex"]
                    val caller = callerDescriptor?.let { MethodDescriptorCodec.method(it, classLoader) }
                    if (caller == null) callerDecodeFailed = true else add(caller)
                }
            }
            if (method == null || method.parameterCount != 0 || method.returnType != Boolean::class.javaPrimitiveType ||
                callerCount == null || callers == null || callerDecodeFailed || callers.size != callerCount
            ) {
                suCacheFailed = true
            } else suTargets += GateTarget(method, callers)
        }
        if (suCacheFailed || suTargets.size != suCount) {
            DebugLog.w(TAG, "cached local-su family is incomplete; rejecting only that subfeature")
            suTargets.clear()
        }
        return SecurityCenterSafetyTargets(
            localSuPredicates = suTargets,
            safePayGetter = target("safepay"),
            rootScanGetter = target("root_scan"),
            usbInterceptGetter = target("usb_intercept"),
        )
    }

    private fun install(targets: SecurityCenterSafetyTargets) {
        var installed = 0
        targets.localSuPredicates.forEach {
            installed += installDynamicFalse(it, PlatformSettingsKeys.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK)
        }
        installed += installDynamicFalse(targets.usbInterceptGetter,
            PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT)
        installed += installDynamicFalse(targets.safePayGetter,
            PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN)
        installed += installDynamicFalse(targets.rootScanGetter,
            PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM)
        DebugLog.i(TAG, "dynamic gates installed=$installed process=${hookParam.processName}")
    }

    private fun installDynamicFalse(target: GateTarget?, preferenceKey: String): Int {
        val enabled by hookSetting(false) { Preferences.getBoolean(preferenceKey, false) }
        target ?: return 0
        return runCatching {
            target.callers.forEach(::deoptimize)
            val method = target.method
            method.isAccessible = true
            deoptimize(method)
            method.hook("security_checks_${MethodDescriptorCodec.descriptor(method)}") {
                before { param ->
                    HookFailurePolicy.open(TAG, "${method.name}.before", Unit) {
                        if (enabled) param.result = false
                    }
                }
            }
            1
        }.onFailure { DebugLog.hookFailed(TAG, target.method.toGenericString(), it) }.getOrDefault(0)
    }

}
