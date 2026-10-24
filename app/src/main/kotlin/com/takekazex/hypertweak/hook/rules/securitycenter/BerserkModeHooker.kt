package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Stable identity for the persistent target cache. */
internal object BerserkModeFeature : DexFeature {
    override val id = "securitycenter.berserk"
    override val schema = 1
}

/** Persistent target descriptor compute/decode. */
internal object BerserkModeCache {
    const val WILD_SUPPORT_TEXT = "isSupport wild model "
    const val LEGACY_SUPPORT_KEY = "support_wild_boost_bat_perf"

    fun descriptors(session: com.takekazex.hypertweak.hook.base.dex.DexSession): Map<String, String> =
        session.dexKit { bridge ->
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
            (wild + legacy)
                .filter { it.isMethod && Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "boolean" }
                .map { it.descriptor }
                .distinct()
                .mapIndexed { index, descriptor -> "gate." + index to descriptor }
                .toMap()
        } ?: emptyMap()

    fun decode(stored: Map<String, String>, loader: ClassLoader): List<java.lang.reflect.Method>? =
        stored.entries
            .filter { it.key.startsWith("gate.") }
            .sortedBy { it.key }
            .mapNotNull { (_, descriptor) ->
                com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.method(descriptor, loader)
            }
            .filter(::isStaticBooleanGate)
            .ifEmpty { null }

    fun isStaticBooleanGate(method: java.lang.reflect.Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType
}

/**
 * Shows Security Center's native Chinese `狂暴模式` label by opening its Wild-mode capability
 * gate. The activation path remains the ROM's own performance-mode path; this hook only changes
 * the support result, matching the MeowCeiler implementation.
 *
 * The targets are cached descriptors rather than a fresh query per process. This resolution used
 * to run as an uncached `DexRuntime.query` in every Security Center process - five times during a
 * single boot (568/532/496/476/295 ms), because they start within the same second and each found
 * an empty cache.
 */
object BerserkModeHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    /** Display-only: the label is evaluated when the page is read, so off means not installed. */
    override val installPolicy: InstallPolicy = InstallPolicy.WHEN_ENABLED

    override fun isFeatureEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE, false)

    private const val TAG = "BerserkMode"
    private const val PACKAGE = "com.miui.securitycenter"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!isFeatureEnabled()) {
            DebugLog.hookSkippedDebug(TAG, "show Berserk mode", "disabled")
            return
        }
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "show Berserk mode", "source APK unavailable")
            return
        }
        prepareDexTargets(
            name = BerserkModeFeature.id,
            resolve = { resolve(apkPath) },
            install = ::install,
        )
    }

    private fun resolve(apkPath: String): List<Method>? {
        val values = DexRuntime.resolve(
            feature = BerserkModeFeature,
            apkPath = apkPath,
            cacheDir = dexCacheDirectory,
            cacheEmpty = true,
            appInfo = hookParam.appInfo,
            compute = BerserkModeCache::descriptors,
            decode = { stored, _ -> decode(stored) },
        )
        return values
    }

    private fun decode(stored: Map<String, String>): List<Method>? =
        BerserkModeCache.decode(stored, classLoader)

    private fun install(methods: List<Method>) {
        var installed = 0
        methods.distinctBy(Method::toGenericString).forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook("berserk_mode_" + method.toGenericString()) {
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
            DebugLog.i(TAG, "Berserk-mode support gates opened boundaries=" + installed)
        }
    }

}
