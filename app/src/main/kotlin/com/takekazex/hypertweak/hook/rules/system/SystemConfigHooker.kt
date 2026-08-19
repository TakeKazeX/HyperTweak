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

        // Capture the desired state now, at hook-install time: on the system_server path
        // initPreferences() has already run (from onModuleLoaded), so this read is reliable. The
        // SystemConfig() singleton can be constructed at any point during early boot, so reading the
        // preference lazily inside the constructor callback risks seeing a not-yet-loaded value and
        // silently skipping the removal forever (the singleton never re-runs its constructor).
        val removeGms = Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)
        if (removeGms) {
            // Constructor hook: fires only when SystemConfig() is constructed after this module's
            // hooks install. The singleton is built inside `synchronized (SystemConfig.class)` in
            // getInstance(), so this after-hook runs with the map mutation already under that lock.
            clzSystemConfig.findConstructorOrNull { noParams() }?.let { ctor ->
                ctor.hook {
                    after { param -> removeGmsRestrictions(param.thisObject) }
                }
            } ?: Log.w(TAG, "SystemConfigHooker: no-arg SystemConfig() unavailable; relying on getInstance()")

            // OS4 (Android 16) fallback: on this build SystemConfig.getInstance() is first called
            // during very early system_server bootstrap — BEFORE user modules are injected — so the
            // singleton already exists and the constructor hook never fires (verified on-device
            // 2026-08-19: ReadingSystemConfig at boot, HOOK_OK 4s later, features never removed).
            // getInstance() is called repeatedly after boot and returns the fully-built singleton;
            // hook it so the first call after module injection removes the features. The removal
            // re-enters the SystemConfig.class monitor (see removeGmsRestrictions) so the ArrayMap
            // mutation never races a concurrent getAvailableFeatures() reader.
            clzSystemConfig.findMethodOrNull { name("getInstance") }?.hook {
                after { param -> removeGmsRestrictions(param.result) }
            } ?: Log.w(TAG, "SystemConfigHooker: getInstance() unavailable; removal may not run")
        }
        Log.i(TAG, "SystemConfigHooker: removeGmsRestrictions=${if (removeGms) "enabled" else "disabled"} ==== waiting for SystemConfig")
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
            // Synchronize on the SystemConfig class object — the same monitor getInstance()
            // holds while building the singleton — so the mAvailableFeatures mutation from this
            // getInstance() after-hook (which runs just after the original returns, i.e. after the
            // lock was released) cannot be observed mid-iteration by a concurrent
            // getAvailableFeatures() caller. Re-entrant when already arrived via the constructor
            // branch (which runs inside that lock).
            kotlin.synchronized(instance.javaClass) {
                GMS_FEATURES.forEach { feature -> method.invoke(instance, feature) }
            }
            Log.i(TAG, "SystemConfigHooker: removed CN GMS features ${GMS_FEATURES.contentToString()}")
        } catch (t: Throwable) {
            Log.e(TAG, "SystemConfigHooker: Failed to execute removeGmsRestrictions", t)
        }
    }
}
