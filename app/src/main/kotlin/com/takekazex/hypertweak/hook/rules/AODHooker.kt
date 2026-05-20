package com.takekazex.hypertweak.hook.rules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker

object AODHooker : StaticHooker() {
    override fun onHook() {
        val clzFeatureParser = "miui.util.FeatureParser".toClassOrNull() ?: return

        clzFeatureParser.resolve().firstMethodOrNull {
            name = "getBoolean"
            parameters(String::class, Boolean::class)
        }?.hook {
            val key = args[0] as String
            if (key == "support_aod_fullscreen" && Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)) {
                result(true)
            } else {
                result(proceed())
            }
        }

    }
}
