package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import java.lang.reflect.Method

object SystemConfigHooker : StaticHooker() {
    private var removeFeatureMethod: Method? = null

    override fun onHook() {
        val clzSystemConfig = "com.android.server.SystemConfig".toClassOrNull() ?: return

        clzSystemConfig.findConstructorOrNull {}?.hook {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) {
                    removeGmsRestrictions(param.thisObject)
                }
            }
        }

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
        if (instance == null) return
        try {
            if (removeFeatureMethod == null) {
                removeFeatureMethod = instance.javaClass.getDeclaredMethod("removeFeature", String::class.java).apply {
                    isAccessible = true
                }
            }
            removeFeatureMethod?.invoke(instance, "cn.google.services")
            removeFeatureMethod?.invoke(instance, "com.google.android.feature.services_updater")
        } catch (t: Throwable) {
            // Ignore
        }
    }
}
