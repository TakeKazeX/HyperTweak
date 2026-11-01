package com.takekazex.hypertweak.hook.rules.securitycenter.sidebar

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

private enum class GameBoosterFeature(override val id: String) : DexFeature {
    GUNSIGHT("securitycenter.gamebooster.gunsight"), MACRO("securitycenter.gamebooster.macro");
    override val schema = 2
}

/** Only future native decisions are overridden; no host lists, model or service state is written. */
object SecurityCenterGameBoosterHooker : DynamicHooker() {
    private const val TAG = "SecurityCenterGameBooster"
    private val gunsight by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_FORCE_UNLOCK_GUNSIGHT, false)
    }
    private val macro by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_FORCE_UNLOCK_MACRO, false)
    }
    @Volatile private var retired = false
    init { generationCleanup { retired = true } }
    private data class Targets(val methods: Map<String, Method>, val callers: List<Method>,
        val invalid: Method, val contexts: List<Method>, val contextCallers: List<Method>)

    internal fun ownsProcess(process: String) = process in setOf(SecurityCenterDockHost.PACKAGE,
        SecurityCenterDockHost.PROCESS, "${SecurityCenterDockHost.PACKAGE}.remote")

    override fun onHook() {
        if (hookParam.packageName != SecurityCenterDockHost.PACKAGE || !ownsProcess(hookParam.processName)) return
        retired = false
        val apk = hookParam.appInfo?.sourceDir ?: return
        // Resolve independently: a changed macro contract must not disable gunsight (or vice versa).
        for (feature in GameBoosterFeature.entries) prepareDexTargets(name = feature.id, resolve = {
            DexRuntime.resolve(feature = feature, apkPath = apk, cacheDir = dexCacheDirectory,
                compute = {
                    val resolver = SecurityCenterGameBoosterResolver(DexRuntime.operands(apk))
                    if (feature == GameBoosterFeature.GUNSIGHT) resolver.gunsight() else resolver.macro()
                }, decode = { values, _ -> decode(values, feature) })
        }, install = { targets -> install(targets, feature) })
    }

    private fun decode(values: Map<String, String>, feature: GameBoosterFeature): Targets? = runCatching {
        val roles = if (feature == GameBoosterFeature.GUNSIGHT) setOf("gunsight") else setOf("macro", "setting")
        val methods = roles.associateWith { requireNotNull(MethodDescriptorCodec.method(values.getValue("m.$it"), classLoader)) }
        check(methods.values.all { it.returnType == Boolean::class.javaPrimitiveType })
        check(methods.all { (role, method) -> method.parameterTypes.toList() == when (role) {
            "gunsight" -> listOf(String::class.java)
            "macro" -> listOf(android.content.Context::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            else -> listOf(Boolean::class.javaPrimitiveType)
        } })
        fun decodeMethods(key: String) = values.getValue(key).lineSequence().filter(String::isNotBlank).map {
            requireNotNull(MethodDescriptorCodec.method(it, classLoader))
        }.toList()
        val invalid = requireNotNull(MethodDescriptorCodec.method(values.getValue("model.invalid"), classLoader))
        check(invalid.returnType == Boolean::class.javaPrimitiveType && invalid.parameterCount == 0)
        Targets(methods, decodeMethods("callers"), invalid, decodeMethods("model.contexts"), decodeMethods("model.callers"))
    }.onFailure { DebugLog.w(TAG, "${feature.id} contract unavailable; retaining native behavior", it) }.getOrNull()

    private fun install(targets: Targets, feature: GameBoosterFeature) {
        (targets.callers + targets.contextCallers).distinct().forEach { deoptimize(it) }
        val scope = GameBoosterModelScope()
        targets.contexts.forEach { method ->
            deoptimize(method)
            method.hook {
                before { _ -> scope.enter() }
                after { _ -> scope.leave() }
            }
        }
        deoptimize(targets.invalid)
        targets.invalid.hook { before { param ->
            if (scope.bypass(!retired && (if (feature == GameBoosterFeature.GUNSIGHT) gunsight else macro))) param.result = false
        } }
        targets.methods.values.forEach { method ->
            deoptimize(method)
            method.hook { before { param ->
                if (!retired && (if (feature == GameBoosterFeature.GUNSIGHT) gunsight else macro)) param.result = true
            } }
        }
        DebugLog.hookRegistered(TAG, feature.id)
    }
    fun refreshSettings(): Boolean = true // Shared participant acceptance refreshes hookSetting delegates.
    override fun onPrepareHotReload() { retired = true }
}
