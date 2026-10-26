package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.cachedDeclaredMethod
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.util.logging.DebugLog
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private object DarkModeScheduleFeature : DexFeature {
    override val id = "system.dark_mode_schedule"
    override val schema = 1
}

private data class DarkModeScheduleAccess(
    val predicate: Method, val configuration: Method, val wait: Field,
    val unregister: Method, val update: Method, val lock: Field, val callers: List<Method>,
) {
    companion object {
        fun decode(values: Map<String, String>, loader: ClassLoader): DarkModeScheduleAccess? {
            fun method(key: String) = values[key]?.let { MethodDescriptorCodec.method(it, loader) }
            fun field(key: String) = values[key]?.let { MethodDescriptorCodec.field(it, loader) }
            val access = DarkModeScheduleAccess(method("predicate") ?: return null,
                method("configuration") ?: return null, field("wait") ?: return null,
                method("unregister") ?: return null, method("update") ?: return null, field("lock") ?: return null,
                values["callers"]?.lines()?.takeIf { it.size in 1..64 }?.map {
                    MethodDescriptorCodec.method(it, loader) ?: return null
                } ?: return null)
            val owner = access.predicate.declaringClass
            if (access.predicate.returnType != Boolean::class.javaPrimitiveType || access.predicate.parameterCount != 0 ||
                !Modifier.isPrivate(access.predicate.modifiers) || access.wait.type != Boolean::class.javaPrimitiveType ||
                access.lock.type != Any::class.java || access.wait.declaringClass != owner || access.lock.declaringClass != owner ||
                listOf(access.configuration, access.unregister).any { it.declaringClass != owner || it.parameterCount != 0 || it.returnType != Void.TYPE } ||
                access.update.declaringClass != owner || access.update.returnType != Void.TYPE ||
                !access.update.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))) return null
            return access
        }
    }
}

/** Keeps the native schedule, alarms, configuration delivery and vendor dark-mode mirror intact. */
object DarkModeScheduleHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingDarkModeImmediateSchedule by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_DARK_MODE_IMMEDIATE_SCHEDULE, false)
    }

    private const val TAG = "DarkModeSchedule"
    private const val SERVICES = "/system/framework/services.jar"
    override val installPolicy = InstallPolicy.WHEN_ENABLED
    private var enabled by generationState { false }
    private var access: DarkModeScheduleAccess? by generationState { null }
    override fun isFeatureEnabled() = settingDarkModeImmediateSchedule

    fun refreshSettings(): Boolean {
        Preferences.invalidateRuntimeReadCache()
        enabled = isFeatureEnabled()
        refreshFeatureState()
        return !enabled || (isHooked && access?.let(::recoverPending) == true)
    }

    override fun onHook() {
        enabled = isFeatureEnabled()
        access = null
        val cached = resolve(true)
        if (cached != null) install(cached) else prepareDexTargets(name = DarkModeScheduleFeature.id,
            resolve = { resolve(false) }, install = ::install)
    }
    private fun resolve(cacheOnly: Boolean) = DexRuntime.resolve(
        feature = DarkModeScheduleFeature, apkPath = SERVICES, cacheDir = dexCacheDirectory ?: File("/data/system/hypertweak/cache"), cacheOnly = cacheOnly,
        compute = { DarkModeScheduleResolver(DexRuntime.operands(SERVICES)).resolve() },
        decode = { values, _ -> DarkModeScheduleAccess.decode(values, classLoader) },
    )
    private fun install(target: DarkModeScheduleAccess) {
        target.callers.forEach { check(deoptimize(it)) { "Cannot deoptimize automatic mode caller: $it" } }
        target.predicate.hook("dark_mode_schedule_decision") {
            before { param -> if (enabled && access === target) param.result = true }
        }
        target.configuration.hook("dark_mode_schedule_pending") {
            before { param -> if (enabled && access === target) HookFailurePolicy.open(TAG, "clear pending switch", Unit) {
                param.thisObject.takeIf(target.wait.declaringClass::isInstance)?.let {
                    if (target.wait.getBoolean(it)) target.unregister.invoke(it)
                }
            } }
        }
        access = target
        recoverPending(target)
        DebugLog.hookRegistered(TAG, "automatic schedule contract ready")
    }
    private fun recoverPending(target: DarkModeScheduleAccess): Boolean {
        // ServiceManager returns the local Binder in system_server. Its outer-instance field
        // recovers a living service even on upgrade from an APK which never tracked this feature.
        return HookFailurePolicy.open(TAG, "recover pending schedule", false) {
            val binder = Class.forName("android.os.ServiceManager").cachedDeclaredMethod("getService", String::class.java, accessible = false)
                .invoke(null, "uimode") ?: return@open false
            val outer = generateSequence(binder.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) && it.type == target.predicate.declaringClass }.single()
                .apply { isAccessible = true }.get(binder) ?: return@open false
            val lock = target.lock.get(outer) ?: return@open false
            synchronized(lock) {
                if (enabled && access === target && target.wait.getBoolean(outer)) {
                    target.unregister.invoke(outer)
                    target.update.invoke(outer, 0, 0)
                    DebugLog.i(TAG, "published existing pending automatic switch")
                }
            }
            true
        }
    }
    override fun recoverHotReloadHosts() { refreshSettings() }
    override fun onPrepareHotReload() { enabled = false; access = null }
}
