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
 * Shows Security Center's native Chinese `狂暴模式` label by opening its Wild-mode capability
 * gate. The activation path remains the ROM's own performance-mode path; this hook only changes
 * the support result, matching the MeowCeiler implementation.
 */
object BerserkModeHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "BerserkMode"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val WILD_SUPPORT_TEXT = "isSupport wild model "
    private const val LEGACY_SUPPORT_KEY = "support_wild_boost_bat_perf"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE, false)) {
            DebugLog.hookSkipped(TAG, "show Berserk mode", "disabled")
            return
        }

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "show Berserk mode", "source APK unavailable")
            return
        }
        val methods = resolveMethods(apkPath)
        if (methods.isEmpty()) {
            DebugLog.hookSkipped(TAG, "show Berserk mode", "support gates not found")
            return
        }

        var installed = 0
        methods.distinctBy(Method::toGenericString).forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook("berserk_mode_${method.toGenericString()}") {
                    returnConstant(true)
                }
                installed++
            }.onFailure {
                DebugLog.hookFailed(TAG, method.toGenericString(), it)
            }
        }

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "show Berserk mode", "hook registration failed")
        } else {
            DebugLog.i(TAG, "Berserk-mode support gates opened boundaries=$installed")
        }
    }

    private fun resolveMethods(apkPath: String): List<Method> {
        val resolved = DexKitManager.withBridge(apkPath) { bridge ->
            val wild = bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType("boolean")
                    addUsingString(WILD_SUPPORT_TEXT, StringMatchType.Equals)
                }
            }.toList()
            val legacy = bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType("boolean")
                    addUsingString(LEGACY_SUPPORT_KEY, StringMatchType.Equals)
                }
            }.toList()
            (wild + legacy).mapNotNull(::materialize).filter(::isStaticBooleanGate)
        }
        if (!resolved.isNullOrEmpty()) return resolved

        // Current 13.2.7 fallback: dk.j.F()/G() are the V2/legacy Wild support gates.
        val supportClass = "dk.j".toClassOrNull() ?: return emptyList()
        return supportClass.declaredMethods.filter {
            it.name == "F" || it.name == "G"
        }.filter(::isStaticBooleanGate)
    }

    private fun materialize(data: MethodData): Method? = runCatching {
        data.getMethodInstance(classLoader)
    }.onFailure {
        DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", it)
    }.getOrNull()

    private fun isStaticBooleanGate(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType
}
