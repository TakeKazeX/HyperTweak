package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Modifier

/**
 * Keeps MiSound's floating "multi-app volume" entry away while the volume dialog's own app volume
 * (SystemUI `AppVolumeHooker`) is on.
 *
 * On every volume change `AudioService.sendVolumeUpdate` calls
 * `PlaybackActivityMonitorStubImpl.startPlayerVolumeService(Context,int,int)` (miui-services),
 * whose only effect is a foreground-service start of `com.miui.misound/.playervolume.VolumeUIService`,
 * which then shows MiSound's floating entry. Skipping that start leaves gains, Sound Assistant and
 * MiSound's settings page untouched. An already running service is left alone and stops itself.
 * With the AOSP volume panel on, the module's entry is unavailable, so MiSound's stays.
 */
object MiSoundVolumeEntryHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingAppVolume by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_VOLUME_APP_VOLUME, false)
    }
    private val settingAospVolumePanel by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_VOLUME_PANEL, false)
    }

    override val hookerName = "MiSoundVolumeEntry"
    private const val TAG = "MiSoundVolumeEntry"
    private const val MONITOR = "com.android.server.audio.PlaybackActivityMonitorStubImpl"

    @Volatile private var suppress = false

    override fun onHook() {
        refreshSettings()
        if (!PlatformLevel.isOs4) return
        val type = MONITOR.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, MONITOR, "class not found")
            return
        }
        val int = Int::class.javaPrimitiveType!!
        val start = type.declaredMethods.singleOrNull {
            it.name == "startPlayerVolumeService" && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, int, int))
        } ?: run {
            DebugLog.hookSkipped(TAG, "$MONITOR#startPlayerVolumeService", "signature not found")
            return
        }
        start.hook {
            intercept { chain ->
                if (HookFailurePolicy.open(TAG, "startPlayerVolumeService", false) { suppress }) null else chain.proceed()
            }
        }
        DebugLog.d(TAG, "hooked startPlayerVolumeService suppress=$suppress")
    }

    fun refreshSettings() {
        val next = settingAppVolume && !settingAospVolumePanel
        if (next != suppress) DebugLog.i(TAG, "MiSound floating entry suppressed=$next")
        suppress = next
    }
}
