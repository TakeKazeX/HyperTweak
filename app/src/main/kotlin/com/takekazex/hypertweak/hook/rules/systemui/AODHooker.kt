package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker

object AODHooker : StaticHooker() {
    @Volatile
    private var fullscreenEnabled = false

    override fun onHook() {
        fullscreenEnabled = Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)
        if (!fullscreenEnabled) return

        val clzFeatureParser = "miui.util.FeatureParser".toClassOrNull() ?: return

        clzFeatureParser.findMethodOrNull {
            name("getBoolean")
            parameterTypes(String::class.java, Boolean::class.javaPrimitiveType!!)
        }?.hook {
            before { param ->
                val key = param.args[0] as? String ?: return@before
                if (key == "support_aod_fullscreen") {
                    param.result = true
                }
            }
        }
    }
}
