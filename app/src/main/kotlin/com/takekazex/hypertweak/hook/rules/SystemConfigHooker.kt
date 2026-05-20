package com.takekazex.hypertweak.hook.rules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import java.lang.reflect.Method

object SystemConfigHooker : StaticHooker() {
    private var removeFeatureMethod: Method? = null

    override fun onHook() {
        val clzSystemConfig = "com.android.server.SystemConfig".toClassOrNull() ?: return

        clzSystemConfig.resolve().firstConstructorOrNull()?.hook {
            val ori = proceed()
            if (Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) {
                removeGmsRestrictions(thisObject)
            }
            result(ori)
        }

        clzSystemConfig.resolve().firstMethodOrNull {
            name = "getAvailableFeatures"
        }?.hook {
            val ori = proceed()
            if (Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) {
                removeGmsRestrictions(thisObject)
            }
            result(ori)
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
