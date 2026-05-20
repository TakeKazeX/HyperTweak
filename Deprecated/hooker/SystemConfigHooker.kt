package com.ink.tweaks.hooker

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.*

object SystemConfigHooker : YukiBaseHooker() {
    override fun onHook() {
        val systemConfigClass = "com.android.server.SystemConfig".toClass()

        systemConfigClass.constructor().hook {
            after {
                removeGmsRestrictions(instance)
            }
        }

        systemConfigClass.method {
            name = "getAvailableFeatures"
        }.hook {
            after {
                removeGmsRestrictions(instance)
            }
        }
    }

    private fun removeGmsRestrictions(instance: Any) {
        try {
            val removeFeatureMethod = instance.javaClass.getDeclaredMethod("removeFeature", String::class.java)
            removeFeatureMethod.isAccessible = true
            removeFeatureMethod.invoke(instance, "cn.google.services")
            removeFeatureMethod.invoke(instance, "com.google.android.feature.services_updater")
        } catch (t: Throwable) {
            // Ignore
        }
    }
}
