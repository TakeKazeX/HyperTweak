package com.takekazex.hypertweak.hook.rules.systemui

import android.media.MediaActionSound
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/** Suppresses the SystemUI screenshot shutter sound when the lockscreen bar is hidden. */
object SystemUiScreenshotSoundHooker : StaticHooker() {
    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)) {
            DebugLog.hookSkipped("SystemUiScreenshotSound", "MediaActionSound#play", "disabled")
            return
        }

        runCatching {
            MediaActionSound::class.java.getMethod("play", Int::class.javaPrimitiveType).hook {
                before { param ->
                    if ((param.args.getOrNull(0) as? Number)?.toInt() == MediaActionSound.SHUTTER_CLICK) {
                        param.result = null
                    }
                }
            }
            DebugLog.d("SystemUiScreenshotSound", "screenshot shutter sound suppressed")
        }.onFailure {
            DebugLog.hookFailed("SystemUiScreenshotSound", "MediaActionSound#play(Int)", it)
        }
    }
}
