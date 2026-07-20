package com.takekazex.hypertweak.hook.rules.system

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import java.lang.reflect.Method

object SystemConfigHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private var removeFeatureMethod: Method? = null

    override fun onHook() {
        val clzSystemConfig = "com.android.server.SystemConfig".toClassOrNull()
        if (clzSystemConfig == null) {
            Log.e("HyperTweak", "SystemConfigHooker: Failed to find com.android.server.SystemConfig class")
            return
        }

        // Try to hook constructor
        clzSystemConfig.findConstructorOrNull {}?.hook {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) {
                    removeGmsRestrictions(param.thisObject)
                }
            }
        }

        // Try to hook getInstance
        clzSystemConfig.findMethodOrNull {
            name("getInstance")
        }?.hook {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) {
                    removeGmsRestrictions(param.result)
                }
            }
        }

        // Try to hook getAvailableFeatures
        clzSystemConfig.findMethodOrNull {
            name("getAvailableFeatures")
        }?.hook {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) {
                    removeGmsRestrictions(param.thisObject)
                }
            }
        }
    }

    private fun removeGmsRestrictions(instance: Any?) {
        if (instance == null) {
            Log.w("HyperTweak", "SystemConfigHooker: removeGmsRestrictions called with null instance")
            return
        }
        try {
            if (removeFeatureMethod == null) {
                removeFeatureMethod = instance.javaClass.getDeclaredMethod("removeFeature", String::class.java).apply {
                    isAccessible = true
                }
            }
            removeFeatureMethod?.invoke(instance, "cn.google.services")
            removeFeatureMethod?.invoke(instance, "com.google.android.feature.services_updater")
        } catch (t: Throwable) {
            Log.e("HyperTweak", "SystemConfigHooker: Failed to execute removeGmsRestrictions", t)
        }
    }
}
