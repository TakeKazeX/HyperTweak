package com.takekazex.hypertweak.hook.rules.community

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.CommunitySettings
import com.takekazex.hypertweak.settings.contract.CommunitySettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private object CommunityFeature : DexFeature {
    override val id = "community.feedback"
    override val schema = 1
}

object CommunityHooker : StaticHooker() {
    private val block by hookSetting(false) { Preferences.getBoolean(CommunitySettingsKeys.KEY_COMMUNITY_BLOCK_DETECTION, false) }
    private val model by hookSetting(null as String?) { CommunitySettings.model(
        Preferences.getBoolean(CommunitySettingsKeys.KEY_COMMUNITY_DEVICE_MODIFY, false), Preferences.getString(CommunitySettingsKeys.KEY_COMMUNITY_MODEL, "")) }

    override fun onHook() {
        val apk = hookParam.appInfo?.sourceDir ?: return
        val cached = resolve(apk, true)
        if (cached != null) install(cached)
        else prepareDexTargets(name = CommunityFeature.id, resolve = { resolve(apk, false) }, install = ::install)
    }
    private fun resolve(apk: String, cacheOnly: Boolean): CommunityAccess? = DexRuntime.resolve(
        feature = CommunityFeature, apkPath = apk, cacheDir = dexCacheDirectory, cacheOnly = cacheOnly,
        compute = { CommunityResolver(DexRuntime.operands(apk)).resolve() },
        decode = { values, _ -> CommunityAccess.decode(values, classLoader) })

    private fun install(access: CommunityAccess) {
        // Bring AOT-inlined builders back through the managed boundary.
        check((access.callers + access.build).all { deoptimize(it) }) { "Community feedback deoptimization failed" }
        access.build.hook("community_feedback") {
            after { param ->
                if (param.throwable != null) return@after
                HookFailurePolicy.open("Community", "feedback payload", Unit) {
                    access.apply(param.thisObject, block, model)
                }
            }
        }
        DebugLog.hookRegistered("Community", "feedback payload ready process=${hookParam.processName}")
    }
}

internal data class CommunityAccess(val build: Method, val callers: List<Method>, val list: Field,
    val client: Field, val model: Field, val app: Field) {
    /** Only newly constructed payloads are changed; no host-global state needs restoration. */
    fun apply(payload: Any, block: Boolean, replacement: String?) {
        if (block) {
            val original = list.get(payload) as? List<*>
            if (original != null) {
                val filtered = original.filterNot { item -> item != null && app.declaringClass.isInstance(item) &&
                    CommunitySettings.blocked(app.get(item) as? String) }
                if (filtered.size != original.size) list.set(payload, ArrayList(filtered))
            }
        }
        replacement?.let { value -> client.get(payload)?.let { model.set(it, value) } }
    }
    companion object {
        fun decode(values: Map<String, String>, loader: ClassLoader): CommunityAccess? {
            fun method(k: String) = values[k]?.let { MethodDescriptorCodec.method(it, loader) }
            fun field(k: String) = values[k]?.let { MethodDescriptorCodec.field(it, loader) }
            val build = method("build") ?: return null
            val producer = method("producer") ?: return null
            val submit = method("submit") ?: return null
            val list = field("list") ?: return null
            val client = field("client") ?: return null
            val model = field("model") ?: return null
            val app = field("app") ?: return null
            if (Modifier.isStatic(build.modifiers) || build.returnType != Void.TYPE ||
                build.parameterTypes.toList() != listOf(String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType) ||
                producer.returnType != String::class.java || !producer.parameterTypes.contentEquals(build.parameterTypes) ||
                submit.returnType != Void.TYPE || submit.parameterTypes.toList() != listOf(String::class.java) ||
                list.type != ArrayList::class.java || list.declaringClass != build.declaringClass ||
                client.declaringClass != build.declaringClass || client.type != model.declaringClass ||
                model.type != String::class.java || app.type != String::class.java ||
                listOf(list, client, model, app).any { Modifier.isStatic(it.modifiers) || Modifier.isFinal(it.modifiers) }) return null
            return CommunityAccess(build, listOf(producer, submit), list, client, model, app)
        }
    }
}
