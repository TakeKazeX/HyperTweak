package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Restores the Security Center power-ranking branch hidden by its MIUI-version helper.
 *
 * The reference implementation resolves boolean methods that read
 * `ro.miui.ui.version.code` and compare against 9, then returns false. We retain the same
 * behaviour but validate the materialized target as a static, no-argument boolean predicate so a
 * broad version-string match cannot cross into an unrelated API.
 */
object PowerRankingHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "PowerRanking"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val VERSION_PROPERTY = "ro.miui.ui.version.code"
    private const val VERSION_THRESHOLD = 9

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING, false)) {
            DebugLog.hookSkipped(TAG, "power ranking", "disabled")
            return
        }

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "power ranking", "source APK unavailable")
            return
        }
        val methods = resolveMethods(apkPath)
        if (methods.isEmpty()) {
            DebugLog.hookSkipped(TAG, "power ranking", "version-gate method not found")
            return
        }

        var installed = 0
        methods.distinctBy(Method::toGenericString).forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook("power_ranking_${method.toGenericString()}") {
                    returnConstant(false)
                }
                installed++
            }.onFailure {
                DebugLog.hookFailed(TAG, method.toGenericString(), it)
            }
        }

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "power ranking", "hook registration failed")
        } else {
            DebugLog.i(TAG, "power-ranking version gates forced open boundaries=$installed")
        }
    }

    private fun resolveMethods(apkPath: String): List<Method> {
        val resolved = DexKitManager.withBridge(apkPath) { bridge ->
            bridge.findMethod {
                matcher {
                    returnType("boolean")
                    addUsingString(VERSION_PROPERTY, StringMatchType.Equals)
                    usingNumbers(VERSION_THRESHOLD)
                }
            }.toList().mapNotNull(::materialize).filter(::isVersionGate)
        }
        if (!resolved.isNullOrEmpty()) return resolved

        // Current 13.2.7 fallback: ae.c.b() returns ro.miui.ui.version.code > 9.
        val versionClass = "ae.c".toClassOrNull() ?: return emptyList()
        return versionClass.declaredMethods.filter {
            it.name == "b" && isVersionGate(it)
        }
    }

    private fun materialize(data: MethodData): Method? = runCatching {
        data.getMethodInstance(classLoader)
    }.onFailure {
        DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", it)
    }.getOrNull()

    private fun isVersionGate(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType
}
