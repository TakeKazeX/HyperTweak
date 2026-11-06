package com.takekazex.hypertweak.hook.rules.market

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.MarketSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Executable

private object MarketFeature : DexFeature {
    override val id = "market.purification"
    // Schema 2 adds the shared tab parser; older caches lack its descriptor.
    override val schema = 2
}

object MarketHooker : StaticHooker() {
    private val enabled by hookSetting(emptySet<String>()) {
        MarketSettingsKeys.keys.filterTo(linkedSetOf()) { Preferences.getBoolean(it, false) }
    }
    override fun onHook() {
        if (hookParam.processName != MarketSettingsKeys.PACKAGE) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        val cached = resolve(apk, true)
        if (cached != null) install(cached)
        else prepareDexTargets(name = MarketFeature.id, resolve = { resolve(apk, false) }, install = ::install)
    }

    private fun resolve(apk: String, cacheOnly: Boolean): Map<String, String>? = DexRuntime.resolve(
        feature = MarketFeature, apkPath = apk, cacheDir = dexCacheDirectory, cacheOnly = cacheOnly,
        compute = { MarketResolver(DexRuntime.operands(apk)).resolve() },
        decode = { values, _ -> values.takeIf { map -> map.values.all { descriptor ->
            if ("-><init>(" in descriptor) MethodDescriptorCodec.constructor(descriptor, classLoader) != null
            else MethodDescriptorCodec.method(descriptor, classLoader) != null
        } } })

    private fun install(values: Map<String, String>) {
        val deoptimized = mutableSetOf<Executable>()
        MarketTargets.all.filterNot { it.tabFilter }.forEach { target ->
            HookFailurePolicy.open("Market", target.descriptor, Unit) {
                val descriptor = values["target:${target.descriptor}"]
                if (descriptor == null) {
                    DebugLog.hookSkipped("Market", target.descriptor, "missing or changed APK contract")
                    return@open
                }
                val method = requireNotNull(MethodDescriptorCodec.method(descriptor, classLoader))
                val callers = values.filterKeys { it.startsWith("caller:$descriptor:") }.values.map { caller ->
                    requireNotNull(if ("-><init>(" in caller) MethodDescriptorCodec.constructor(caller, classLoader)
                        else MethodDescriptorCodec.method(caller, classLoader))
                }
                check((callers + method).all { it in deoptimized || deoptimize(it).also { ok -> if (ok) deoptimized.add(it) } }) {
                    "Market caller deoptimization failed: $descriptor"
                }
                method.hook("market_${target.key}_${method.name}") {
                    if (target.requestMap) after { param ->
                        if (target.key in enabled && param.throwable == null) {
                            HookFailurePolicy.open("Market", "suggestion request", Unit) {
                                MarketTargets.withoutSuggestionAds(param.result)?.let { param.result = it }
                            }
                        }
                    } else before { param ->
                        if (target.key in enabled) param.result = target.value
                    }
                }
            }
        }
        installTabFilter(values, deoptimized)
        DebugLog.hookRegistered("Market", "purification boundaries ready process=${hookParam.processName}")
    }

    /**
     * The store builds both the bottom bar and its pages from `PageConfig.tabs`, so the shared tab
     * parser is filtered once for every enabled tab switch. Removing entries here also fixes the
     * host's derived flags, default tab and back target; the host hides the bar when one tab remains.
     */
    private fun installTabFilter(values: Map<String, String>, deoptimized: MutableSet<Executable>) {
        HookFailurePolicy.open("Market", MarketTargets.TAB_ARRAY, Unit) {
            val descriptor = values["target:${MarketTargets.TAB_ARRAY}"]
            if (descriptor == null) {
                DebugLog.hookSkipped("Market", MarketTargets.TAB_ARRAY, "missing or changed APK contract")
                return@open
            }
            val method = requireNotNull(MethodDescriptorCodec.method(descriptor, classLoader))
            check(method in deoptimized || deoptimize(method).also { ok -> if (ok) deoptimized.add(method) }) {
                "Market tab deoptimization failed: $descriptor"
            }
            val tagOf = requireNotNull(
                runCatching {
                    classLoader.loadClass("com.xiaomi.market.model.TabInfo").getMethod("getTag")
                }.getOrNull()
            ) { "Market tab tag contract missing" }
            method.hook("market_tab_filter") {
                after { param ->
                    if (param.throwable != null) return@after
                    val keys = MarketTabFilter.enabledKeys(enabled)
                    if (keys.isEmpty()) return@after
                    if (!MarketTabGuard.allowsFiltering(classLoader)) return@after
                    HookFailurePolicy.open("Market", "tab filter", Unit) {
                        (param.result as? MutableList<*>)?.let { tabs ->
                            MarketTabFilter.applyTo(tabs, keys) { tab -> tagOf.invoke(tab) as? String }
                        }
                    }
                }
            }
        }
    }
}
