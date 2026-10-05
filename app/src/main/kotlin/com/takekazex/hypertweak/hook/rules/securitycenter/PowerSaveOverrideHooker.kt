package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.ContentResolver
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
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

        val transition = resolveTransition() ?: run {
            DebugLog.hookSkipped(TAG, "power-save overrides", "entry transaction missing or ambiguous")
            return
        }
        // Scope the framework interception to the real host operation. Restoration and unrelated
        // writes with the same key/value must still run, even when the master switch is enabled.
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

    private fun resolveTransition(): Method? {
        val apkPath = hookParam.appInfo?.sourceDir ?: return null
        return DexKitManager.withBridge(apkPath) { bridge ->
            bridge.findMethod {
                matcher {
                    returnType("void")
                    paramTypes(*PowerSaveTransitionContract.parameterTypes)
                    PowerSaveTransitionContract.markers.forEach { addUsingString(it, StringMatchType.Equals) }
                }
            }.singleOrNull()?.let { data ->
                // The private transaction can itself be inlined by the provider's wrapper.
                data.callers.forEach { caller ->
                    HookFailurePolicy.open(TAG, "deoptimize entry caller", Unit) {
                        deoptimize(caller.getMethodInstance(classLoader))
                    }
                }
                data.getMethodInstance(classLoader)
            }
        }
    }

    /** The verified string/int writers persist a small integer setting. */
    private fun intValueOf(value: Any?): Int? = when (value) {
        is Int -> value
        is String -> value.toIntOrNull()
        else -> null
    }

}
