package com.takekazex.hypertweak.hook.rules.analytics

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AnalyticsSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import org.json.JSONObject
import java.lang.reflect.Method

object AnalyticsTelemetryHooker : StaticHooker() {
    private object Feature : DexFeature {
        override val id = "analytics.block_telemetry"
        override val schema = 1
    }

    private data class Targets(
        val blockedTasks: List<Method>,
        val emptyCollectors: List<Method>,
        val radio: Method,
        val offline: List<Method>,
        val legacy: Method,
    )

    private val legacyTelemetryCallers = setOf(
        "Logger", "Dispatcher", "Packer", "RealtimeSender", "AdEventDispatcher", "AdEventRealTimeSender",
    )

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(AnalyticsSettingsKeys.KEY_ANALYTICS_BLOCK_TELEMETRY, false)
    }

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        val cached = resolve(apk, cacheOnly = true)
        if (cached != null) installDexContract { install(cached) }
        else prepareDexTargets(Feature.id, { resolve(apk, cacheOnly = false) }, ::install)
    }

    private fun resolve(apk: String, cacheOnly: Boolean): Targets? = DexRuntime.resolve(
        feature = Feature, apkPath = apk, cacheDir = dexCacheDirectory, cacheOnly = cacheOnly,
        compute = { AnalyticsTelemetryResolver(DexRuntime.operands(apk)).resolve() },
        decode = { values, _ ->
            val members = DexContractMembers(values, classLoader)
            Targets(members.methods("blockedTasks"), members.methods("emptyCollectors"),
                members.method("radio"), members.methods("offline"), members.method("legacy")).also {
                require(it.blockedTasks.size == 3 && it.blockedTasks.all { method -> method.returnType == Void.TYPE })
                require(it.emptyCollectors.size == 4 && it.emptyCollectors.all { method -> method.returnType == JSONObject::class.java })
                require(it.radio.returnType == JSONObject::class.java)
                require(it.offline.size == 2 && (it.offline + it.legacy).all { method -> method.returnType == Boolean::class.javaPrimitiveType })
            }
        },
    )

    private fun install(targets: Targets) {
        val methods = targets.blockedTasks + targets.emptyCollectors + targets.offline + targets.radio + targets.legacy
        check(methods.size == methods.distinct().size)
        methods.forEach { method -> check(deoptimize(method)) { "Cannot prepare Analytics boundary ${method.name}" } }
        (targets.blockedTasks + targets.emptyCollectors).forEach { block(it) { null } }
        targets.offline.forEach { block(it) { false } }
        block(targets.radio) { JSONObject() }
        targets.legacy.hook("analytics_legacy_telemetry") {
            intercept { chain ->
                val caller = chain.args.getOrNull(1) as? String
                if (enabled && caller in legacyTelemetryCallers) true else chain.proceed()
            }
        }
        DebugLog.i("AnalyticsTelemetry", "Installed telemetry boundaries: ${methods.size}")
    }

    private fun block(method: Method, result: () -> Any?) {
        method.hook("analytics_telemetry") {
            intercept { chain -> if (enabled) result() else chain.proceed() }
        }
    }
}
