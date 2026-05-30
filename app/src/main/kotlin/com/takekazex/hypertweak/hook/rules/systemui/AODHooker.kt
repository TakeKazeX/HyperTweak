package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker

object AODHooker : StaticHooker() {
    override fun onHook() {
        val clzFeatureParser = "miui.util.FeatureParser".toClassOrNull() ?: return

        clzFeatureParser.findMethodOrNull {
            name("getBoolean")
            parameterTypes(String::class.java, Boolean::class.javaPrimitiveType!!)
        }?.hook {
            before { param ->
                val key = param.args[0] as? String ?: return@before
                if (key == "support_aod_fullscreen" && Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)) {
                    param.result = true
                }
            }
        }
    }
}
