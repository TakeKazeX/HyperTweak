package com.takekazex.hypertweak.hook.rules.system

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

object SystemConfigHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "HyperTweak"
    private val GMS_FEATURES = arrayOf(
        "cn.google.services",
        "com.google.android.feature.services_updater"
    )

    // Resolved once from the SystemConfig class; the actual map mutation runs a single time.
    @Volatile
    private var removeFeatureMethod: Method? = null
    private val featuresRemoved = AtomicBoolean(false)

    override fun onHook() {
        val clzSystemConfig = "com.android.server.SystemConfig".toClassOrNull()
        if (clzSystemConfig == null) {
            Log.e(TAG, "SystemConfigHooker: Failed to find com.android.server.SystemConfig class")
            return
        }

        // Resolve removeFeature(String) once and cache it. The removal is done from the constructor
        // only, while the singleton is still being built and before getInstance() publishes it, so
        // the ArrayMap is not being mutated on a thread that a concurrent getSystemAvailableFeatures()
        // caller is iterating on a binder thread — the old getAvailableFeatures() per-call hook did
        // exactly that and risked a ConcurrentModificationException/AIOOBE in the victim thread.
        removeFeatureMethod = runCatching {
            clzSystemConfig.getDeclaredMethod("removeFeature", String::class.java)
                .apply { isAccessible = true }
        }.onFailure {
            Log.e(TAG, "SystemConfigHooker: removeFeature(String) is unavailable", it)
        }.getOrNull()
        if (removeFeatureMethod == null) return

        // Bind the no-arg SystemConfig() constructor explicitly. SystemConfig also declares test
        // constructors ((boolean) and (boolean, Injector)); the previous empty findConstructorOrNull {}
        // query could bind whichever one the query happened to return first.
        val constructor = clzSystemConfig.findConstructorOrNull { noParams() }
        if (constructor == null) {
            Log.e(TAG, "SystemConfigHooker: no-arg SystemConfig() constructor is unavailable")
            return
        }
        constructor.hook {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) {
                    removeGmsRestrictions(param.thisObject)
                }
            }
        }
    }

    private fun removeGmsRestrictions(instance: Any?) {
        if (instance == null) {
            Log.w(TAG, "SystemConfigHooker: removeGmsRestrictions called with null instance")
            return
        }
        val method = removeFeatureMethod ?: return
        // Perform the removal exactly once for the lifetime of the process.
        if (!featuresRemoved.compareAndSet(false, true)) return
        try {
            GMS_FEATURES.forEach { feature -> method.invoke(instance, feature) }
        } catch (t: Throwable) {
            Log.e(TAG, "SystemConfigHooker: Failed to execute removeGmsRestrictions", t)
        }
    }
}
