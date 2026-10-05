package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Modifier

/** Stable framework lifecycle boundary; no ROM version, phase number or obfuscated member table. */
object SystemServerReadinessHooker : StaticHooker() {
    override fun onHook() {
        val owner = "com.android.server.SystemServiceManager".toClassOrNull() ?: return
        val boundaries = owner.declaredMethods.filter {
            it.name == "startBootPhase" && !Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE && it.parameterTypes.lastOrNull() == Int::class.javaPrimitiveType &&
                it.parameterTypes.dropLast(1).none(Class<*>::isPrimitive)
        }
        if (boundaries.isEmpty()) {
            DebugLog.w("SystemReadiness", "framework boot-phase boundary unavailable; using bounded readiness polling")
            return
        }
        boundaries.forEach { method ->
            deoptimize(method)
            method.hook("system_settings_readiness_${method.parameterCount}") {
                after { SystemServerStartupRetry.onLifecycleEvent() }
            }
        }
    }
    override fun onPrepareHotReload() = SystemServerStartupRetry.cancelAll()
}
