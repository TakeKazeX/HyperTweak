package com.ink.tweaks.hooker

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.*

object AODHooker : YukiBaseHooker() {
    override fun onHook() {
        "miui.util.FeatureParser".toClass().method {
            name = "getBoolean"
            param(String::class.java, Boolean::class.javaPrimitiveType!!)
        }.hook {
            after {
                if (args(0).string() == "support_aod_fullscreen") {
                    result = true
                }
            }
        }

        "miui.util.FeatureParser".toClass().method {
            name = "getInteger"
            param(String::class.java, Int::class.javaPrimitiveType!!)
        }.hook {
            after {
                if (args(0).string() == "aon_screen_off_fps") {
                    result = 0
                }
            }
        }
    }
}
