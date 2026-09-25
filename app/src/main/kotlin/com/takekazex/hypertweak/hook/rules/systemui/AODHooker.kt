package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker

object AODHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val FULLSCREEN_FEATURE = "support_aod_fullscreen"

    @Volatile
    private var fullscreenSupported = false

    /** True when the ROM supports full-screen AOD natively or this module enables it. */
    fun isFullscreenSupported(): Boolean = fullscreenSupported

    override fun onPrepareHotReload() {
        fullscreenSupported = false
    }

    override fun onHook() {
        val fullscreenEnabled = Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)
        val clzFeatureParser = "miui.util.FeatureParser".toClassOrNull() ?: run {
            fullscreenSupported = fullscreenEnabled
            return
        }
        val getBoolean = clzFeatureParser.findMethodOrNull {
            name("getBoolean")
            parameterTypes(String::class.java, Boolean::class.javaPrimitiveType!!)
        } ?: run {
            fullscreenSupported = fullscreenEnabled
            return
        }
        val nativeSupport = runCatching {
            getBoolean.invoke(null, FULLSCREEN_FEATURE, false) as? Boolean == true
        }.getOrDefault(false)
        fullscreenSupported = fullscreenEnabled || nativeSupport
        if (!fullscreenEnabled) return

        getBoolean.hook {
            before { param ->
                val key = param.args[0] as? String ?: return@before
                if (key == FULLSCREEN_FEATURE) {
                    param.result = true
                }
            }
        }
    }
}
