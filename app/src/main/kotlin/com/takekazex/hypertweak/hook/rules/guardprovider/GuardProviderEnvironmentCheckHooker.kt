package com.takekazex.hypertweak.hook.rules.guardprovider

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private object GuardProviderEnvironmentFeature : DexFeature {
    override val id = "guardprovider.environment"
    override val schema = 3
}

private data class GuardProviderEnvironmentTargets(val predicate: Method, val callers: List<Method>)
private data class GuardProviderPredicateCandidate(val method: MethodData, val callers: List<MethodData>)

private const val TAG = "GuardProviderEnvironment"
private const val PACKAGE = "com.miui.guardprovider"
private const val SU_BIN = "/system/bin/su"
private const val SU_XBIN = "/system/xbin/su"
private const val SU_DATA = "/data/local/su"
private val LOOP_OPS = setOf("aget-object", "add-int/lit8")

/** Dynamically gates only the verified local su predicates; the existing preference key is retained. */
object GuardProviderEnvironmentCheckHooker : DynamicHooker() {
    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        val apkPath = hookParam.appInfo?.sourceDir?.takeIf(String::isNotBlank) ?: run {
            DebugLog.hookSkipped(TAG, "GuardProvider environment checks", "source APK unavailable")
            return
        }
        prepareDexTargets(
            name = GuardProviderEnvironmentFeature.id,
            resolve = { resolve(apkPath) },
            install = { targets -> install(targets) },
        )
    }

    private fun resolve(apkPath: String): GuardProviderEnvironmentTargets? = DexRuntime.resolve(
        feature = GuardProviderEnvironmentFeature,
        apkPath = apkPath,
        cacheDir = dexCacheDirectory,
        compute = { session ->
            session.dexKit { bridge -> resolveDescriptors(bridge) } ?: emptyMap()
        },
        decode = { values, _ ->
            val descriptor = values["predicate"] ?: return@resolve null
            val predicate = MethodDescriptorCodec.method(descriptor, classLoader)
            val count = values["callers.count"]?.toIntOrNull()?.takeIf { it in 1..256 }
            val callers = count?.let { callerCount -> buildList {
                repeat(callerCount) { index ->
                    val callerDescriptor = values["caller.$index"]
                    val caller = callerDescriptor?.let { MethodDescriptorCodec.method(it, classLoader) }
                    if (caller == null) return@buildList
                    add(caller)
                }
            } }
            if (predicate == null || !isStaticBooleanPredicate(predicate) || count == null ||
                callers == null || callers.size != count
            ) {
                DebugLog.w(TAG, "cached environment predicate/callers did not all materialize; rejecting hook")
                null
            } else GuardProviderEnvironmentTargets(predicate, callers)
        },
    )

    private fun resolveDescriptors(bridge: DexKitBridge): Map<String, String> {
        val classes = runCatching {
            bridge.findClass { matcher { usingEqStrings(SU_DATA, SU_BIN, SU_XBIN) } }.toList()
        }.onFailure { DebugLog.w(TAG, "su-path class query failed", it) }.getOrDefault(emptyList())
        val validated = classes.flatMap { classData ->
            classData.methods.filter(::isLocalSuPredicate).map { method ->
                GuardProviderPredicateCandidate(method, method.callers.filter { it.isMethod }.distinctBy { it.descriptor })
            }
        }.distinctBy { it.method.descriptor }
        val selected = validated.singleOrNull()
        if (selected == null || selected.callers.isEmpty()) {
            DebugLog.w(TAG, "validated local File.exists su-array loop predicates=${validated.size}; preserving native behavior")
            return emptyMap()
        }
        return buildMap {
            put("predicate", selected.method.descriptor)
            put("callers.count", selected.callers.size.toString())
            selected.callers.forEachIndexed { index, caller -> put("caller.$index", caller.descriptor) }
        }
    }

    private fun install(targets: GuardProviderEnvironmentTargets) {
        val method = targets.predicate
        runCatching {
            targets.callers.forEach(::deoptimize)
            method.isAccessible = true
            deoptimize(method)
            method.hook("guardprovider_environment_${MethodDescriptorCodec.descriptor(method)}") {
                before { param ->
                    HookFailurePolicy.open(TAG, "${method.name}.before", Unit) {
                        if (Preferences.getBoolean(
                                Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
                                false
                            )
                        ) param.result = false
                    }
                }
            }
            DebugLog.i(TAG, "dynamic local su predicate installed; deoptimized callers=${targets.callers.size}")
        }.onFailure { DebugLog.hookFailed(TAG, method.toGenericString(), it) }
    }

    private fun isStaticBooleanPredicate(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType

    private fun isLocalSuPredicate(data: MethodData): Boolean = runCatching {
        data.isMethod && Modifier.isStatic(data.accessFlags) && data.paramCount == 0 &&
            data.returnTypeName == "boolean" && data.invokes.any { it.descriptor == "Ljava/io/File;->exists()Z" } &&
            data.usingFields.any { field -> field.usingType.isRead() &&
                (field.field.typeName == "java.lang.String[]" || field.field.typeName == "[Ljava.lang.String;") } &&
            data.opNames.containsAll(LOOP_OPS) && data.opNames.any { it.startsWith("if-") } &&
            data.opNames.any { it.startsWith("goto") }
    }.getOrDefault(false)
}
