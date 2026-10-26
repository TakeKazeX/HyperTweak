package com.takekazex.hypertweak.hook.rules.securitycenter.battery

import com.takekazex.hypertweak.hook.rules.securitycenter.SettingsWriterResolver
import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import android.content.ContentResolver
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import org.luckypray.dexkit.query.enums.StringMatchType

/** Preserve selected settings only inside the host's synchronous power-save entry transaction. */
object PowerSaveOverrideHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "PowerSaveOverride"
    private const val PACKAGE = "com.miui.securitycenter"

    private const val SETTINGS = "android.provider.Settings"
    private const val SECURE = "$SETTINGS\$Secure"
    private const val SYSTEM = "$SETTINGS\$System"

    private val enteringPowerSave = ThreadActivation()

    private val CONTENT_RESOLVER = ContentResolver::class.java

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!PowerSaveOverridePolicy.anyGroupEnabled()) {
            DebugLog.hookSkippedDebug(TAG, "power-save overrides", "all switches off")
            return
        }

        prepareDexTargets(name = PowerSaveOverrideFeature.id,
            resolve = ::resolveTransition, install = ::installTransition)
    }

    override val installPolicy = InstallPolicy.WHEN_ENABLED
    override fun isFeatureEnabled(): Boolean = PowerSaveOverridePolicy.anyGroupEnabled()

    private fun installTransition(target: Pair<Method, List<Method>>) {
        val (transition, callers) = target
        // Scope the framework interception to the real host operation. Restoration and unrelated
        // writes with the same key/value must still run, even when the master switch is enabled.
        callers.forEach { caller -> runCatching { deoptimize(caller) } }
        transition.isAccessible = true
        deoptimize(transition)
        transition.hook("power_save_entry_transaction") {
            intercept { chain ->
                if (chain.args.firstOrNull() == true) enteringPowerSave.within { chain.proceed() }
                else chain.proceed()
            }
        }

        var installed = 0
        for (owner in listOf(SECURE, SYSTEM)) {
            val resolved = settingsWriters(owner)
            if (resolved.isEmpty()) {
                DebugLog.hookSkipped(TAG, "power-save overrides", "$owner writers not found")
                continue
            }
            resolved.forEach { method ->
                if (install(method)) installed++
            }
        }

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "power-save overrides", "hook registration failed")
        } else {
            val groups = PowerSaveOverridePolicy.Group.entries
                .filter(PowerSaveOverridePolicy::isGroupEnabled)
                .joinToString(",")
            DebugLog.i(TAG, "keeping power-save features boundaries=$installed groups=$groups")
        }
    }

    /**
     * Resolves the integer/string Settings writers on one table. The selection rule lives in
     * [SettingsWriterResolver] so it is unit-testable without Android types.
     */
    private fun settingsWriters(className: String): List<Method> {
        val owner = runCatching { Class.forName(className) }.getOrNull() ?: return emptyList()
        return SettingsWriterResolver.select(owner.declaredMethods, CONTENT_RESOLVER)
    }

    private fun install(method: Method): Boolean = runCatching {
        method.isAccessible = true
        deoptimize(method)
        method.hook("power_save_override_${method.declaringClass.name}_${method.name}") {
            before { param ->
                HookFailurePolicy.open(TAG, "skip power-save pin", Unit) {
                    if (!enteringPowerSave.active) return@open
                    val key = param.args.getOrNull(1) as? String ?: return@open
                    val value = intValueOf(param.args.getOrNull(2)) ?: return@open
                    val group = PowerSaveOverridePolicy.groupOf(key, value) ?: return@open
                    if (PowerSaveOverridePolicy.isGroupEnabled(group)) {
                        param.result = true
                    }
                }
            }
        }
        true
    }.onFailure {
        DebugLog.hookFailed(TAG, "${method.declaringClass.name}#${method.name}", it)
    }.getOrDefault(false)

    /**
     * Cached resolution of the entry transaction.
     *
     * The installed hook needs one method, so the result collapses to descriptors. This scan used
     * to run in every Security Center process on the main thread - five times per boot, 903-1470 ms
     * each, 6.1 s total (measured 2026-10-24) - for a target that is identical in all of them.
     */
    private fun resolveTransition(): Pair<Method, List<Method>>? = DexRuntime.resolve(
        feature = PowerSaveOverrideFeature,
        apkPath = hookParam.appInfo?.sourceDir ?: return null,
        cacheDir = dexCacheDirectory,
        cacheEmpty = true,
        appInfo = hookParam.appInfo,
        compute = PowerSaveOverrideCache::descriptors,
        decode = { stored, _ -> PowerSaveOverrideCache.decode(stored, classLoader) },
    )


    private fun intValueOf(value: Any?): Int? = when (value) {
        is Int -> value
        is String -> value.toIntOrNull()
        else -> null
    }

}

/** Persistent feature cache identity. */
internal object PowerSaveOverrideFeature : com.takekazex.hypertweak.hook.base.dex.DexFeature {
    override val id = "securitycenter.power_save_override"
    override val schema = 1
}

/**
 * Compute/decode for the power-save entry transaction.
 *
 * The provider's wrapper can inline the private transaction, so its real callers must be
 * deoptimized for the hook to fire. Callers are stored as descriptors here and deoptimized on
 * install: deoptimization is a per-process side effect, the descriptors are not.
 */
internal object PowerSaveOverrideCache {
    private const val TRANSITION = "transition"
    private const val CALLER_PREFIX = "caller."

    fun descriptors(session: com.takekazex.hypertweak.hook.base.dex.DexSession): Map<String, String> =
        session.dexKit { bridge ->
            val match = runCatching {
                bridge.findMethod {
                    matcher {
                        returnType("void")
                        paramTypes(*PowerSaveTransitionContract.parameterTypes)
                        PowerSaveTransitionContract.markers.forEach {
                            addUsingString(it, org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                        }
                    }
                }.toList()
            }.getOrDefault(emptyList())
            val single = match.singleOrNull() ?: return@dexKit emptyMap()
            buildMap {
                put(TRANSITION, single.descriptor)
                single.callers
                    .filter { it.isMethod }
                    .map { it.descriptor }
                    .distinct()
                    .forEachIndexed { index, descriptor -> put(CALLER_PREFIX + index, descriptor) }
            }
        } ?: emptyMap()

    /** Returns the transaction plus its deoptimization targets. */
    fun decode(
        stored: Map<String, String>,
        loader: ClassLoader,
    ): Pair<java.lang.reflect.Method, List<java.lang.reflect.Method>>? {
        val transition = stored[TRANSITION]?.let {
            com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.method(it, loader)
        } ?: return null
        if (transition.returnType != Void.TYPE || !transition.parameterTypes.map { it.name }
                .toTypedArray().contentEquals(PowerSaveTransitionContract.parameterTypes)) return null
        val callers = stored.entries
            .filter { it.key.startsWith(CALLER_PREFIX) }
            .sortedBy { it.key.removePrefix(CALLER_PREFIX).toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { (_, descriptor) ->
                com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.method(descriptor, loader)
            }
        return transition to callers
    }
}
