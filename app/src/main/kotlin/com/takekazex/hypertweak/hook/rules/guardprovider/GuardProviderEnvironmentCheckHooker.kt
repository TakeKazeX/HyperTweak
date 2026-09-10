package com.takekazex.hypertweak.hook.rules.guardprovider

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Makes GuardProvider's root/su environment predicates report a clean device.
 *
 * The device build contains the direct two-path predicate in
 * `com.xiaomi.onetrack.util.b.a()` and also ships a utility class carrying the
 * complete su-path table. Both DexKit queries are retained here because the
 * latter is how XiaomiHelper survives obfuscation changes; only static,
 * zero-argument boolean methods from those classes are hooked.
 */
object GuardProviderEnvironmentCheckHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "GuardProviderEnvironment"
    private const val PACKAGE = "com.miui.guardprovider"
    private const val SU_BIN = "/system/bin/su"
    private const val SU_XBIN = "/system/xbin/su"
    private const val SU_DATA = "/data/local/su"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK, false)) {
            DebugLog.hookSkipped(TAG, "GuardProvider environment checks", "disabled")
            return
        }

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "GuardProvider environment checks", "source APK unavailable")
            return
        }
        val methods = DexKitManager.withBridge(apkPath) { bridge ->
            val resolved = LinkedHashMap<String, Method>()
            val direct = bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType("boolean")
                    addUsingString(SU_BIN, StringMatchType.Equals)
                    addUsingString(SU_XBIN, StringMatchType.Equals)
                }
            }.singleOrNull()
            direct?.let { data ->
                runCatching { data.getMethodInstance(classLoader) }
                    .onFailure { DebugLog.hookFailed(TAG, "${data.className}#${data.methodName}", it) }
                    .getOrNull()
                    ?.takeIf(::isStaticBooleanPredicate)
                    ?.let { method -> resolved[method.toGenericString()] = method }
            }
            bridge.findClass {
                matcher {
                    usingEqStrings(SU_DATA, SU_BIN, SU_XBIN)
                }
            }.forEach { classData ->
                runCatching { classData.getInstance(classLoader) }
                    .onFailure { DebugLog.w(TAG, "failed to materialize su-check class ${classData.name}", it) }
                    .getOrNull()
                    ?.declaredMethods
                    .orEmpty()
                    .filter(::isStaticBooleanPredicate)
                    .forEach { method -> resolved[method.toGenericString()] = method }
            }
            resolved.values.toList()
        } ?: run {
            DebugLog.hookSkipped(TAG, "GuardProvider environment checks", "DexKit unavailable")
            return
        }

        if (methods.isEmpty()) {
            DebugLog.hookSkipped(TAG, "GuardProvider environment checks", "no validated ()Z predicate")
            return
        }

        var installed = 0
        methods.sortedBy(Method::toGenericString).forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook {
                    before { param ->
                        HookFailurePolicy.open(TAG, "${method.name}.before", Unit) {
                            param.result = false
                        }
                    }
                }
                installed++
            }.onFailure { t ->
                DebugLog.hookFailed(TAG, method.toGenericString(), t)
            }
        }
        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "GuardProvider environment checks", "hook registration failed")
        } else {
            DebugLog.i(TAG, "environment predicates disabled boundaries=$installed")
        }
    }

    private fun isStaticBooleanPredicate(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType

}
