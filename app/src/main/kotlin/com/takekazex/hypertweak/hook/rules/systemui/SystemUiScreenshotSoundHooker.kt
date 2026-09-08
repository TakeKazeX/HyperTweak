package com.takekazex.hypertweak.hook.rules.systemui

import android.media.MediaActionSound
import android.net.Uri
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.io.File

/** Keeps the HyperOS screenshot sound when the lockscreen status bar is hidden. */
object SystemUiScreenshotSoundHooker : StaticHooker() {
    private const val HYPEROS_SHUTTER_SOUND_PATH = "/system/media/audio/ui/camera_click.ogg"
    private const val MIUI_SCREENSHOT_PROVIDER =
        "com.miui.screenshot.core.dependencies.ScreenshotSoundProviderImpl"
    private val hyperOsShutterSoundUri = Uri.fromFile(File(HYPEROS_SHUTTER_SOUND_PATH))

    override fun onHook() {
        if (hookParam.packageName == "com.miui.screenshot") {
            hookMiuiScreenshotSoundProvider()
        } else {
            hookSystemUiFallbackSound()
        }
    }

    private fun hookSystemUiFallbackSound() {
        runCatching {
            MediaActionSound::class.java.getMethod("play", Int::class.javaPrimitiveType).hook {
                before { param ->
                    if (
                        Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false) &&
                            (param.args.getOrNull(0) as? Number)?.toInt() == MediaActionSound.SHUTTER_CLICK
                    ) {
                        // The MIUI screenshot process owns the real HyperOS shutter playback.
                        // SystemUI's MediaActionSound is only the AOSP fallback; suppress it here
                        // to avoid playing the same shutter twice for one screenshot.
                        param.result = null
                    }
                }
            }
            DebugLog.hookRegistered("SystemUiScreenshotSound", "SystemUI MediaActionSound#play(Int)")
        }.onFailure {
            DebugLog.hookFailed("SystemUiScreenshotSound", "SystemUI MediaActionSound#play(Int)", it)
        }
    }

    private fun hookMiuiScreenshotSoundProvider() {
        runCatching {
            val providerClass = classLoader.loadClass(MIUI_SCREENSHOT_PROVIDER)
            providerClass.getDeclaredMethod("resolveSoundUri", Boolean::class.javaPrimitiveType).apply {
                isAccessible = true
            }.hook("miui_screenshot_sound_uri") {
                before { param ->
                    if (
                        Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false) &&
                            (param.args.getOrNull(0) as? Boolean) == false
                    ) {
                        param.result = hyperOsShutterSoundUri
                    }
                }
            }
            DebugLog.hookRegistered(
                "SystemUiScreenshotSound",
                "${MIUI_SCREENSHOT_PROVIDER}#resolveSoundUri(Boolean)"
            )
        }.onFailure {
            DebugLog.hookFailed(
                "SystemUiScreenshotSound",
                "${MIUI_SCREENSHOT_PROVIDER}#resolveSoundUri(Boolean)",
                it
            )
        }
    }

}
