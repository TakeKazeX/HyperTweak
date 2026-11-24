package com.takekazex.hypertweak.hook.rules.system

import android.media.AudioManager
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Makes the first media volume-key press change the volume instead of only showing the panel.
 *
 * `AudioService.adjustSuggestedStreamVolume` asks `VolumeController.suppressAdjustment(int,int,boolean)`
 * whether to swallow a press. It returns true for STREAM_MUSIC while the SystemUI panel is hidden and
 * media is not playing; the caller then turns the direction into ADJUST_SAME and only shows the
 * panel. While enabled, a true result for a non-mute STREAM_MUSIC adjustment becomes false, so the
 * press reaches `adjustStreamVolume` (and the custom step, if on). Other streams keep the native rule.
 * The switch is read on every call, so it applies live.
 */
object VolumeFirstPressHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_VOLUME_FIRST_PRESS, false)
    }

    override val hookerName = "VolumeFirstPress"
    private const val TAG = "VolumeFirstPress"
    private const val AUDIO_SERVICE = "com.android.server.audio.AudioService"
    private const val VOLUME_CONTROLLER = "com.android.server.audio.AudioService\$VolumeController"

    @Volatile private var enabled = false

    override fun onHook() {
        refreshSettings()
        val int = Int::class.javaPrimitiveType!!
        val boolean = Boolean::class.javaPrimitiveType!!
        val controller = VOLUME_CONTROLLER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VOLUME_CONTROLLER, "class not found")
            return
        }
        val suppress = controller.declaredMethods.singleOrNull {
            it.name == "suppressAdjustment" && !Modifier.isStatic(it.modifiers) &&
                it.returnType == boolean && it.parameterTypes.contentEquals(arrayOf(int, int, boolean))
        } ?: run {
            DebugLog.hookSkipped(TAG, "$VOLUME_CONTROLLER#suppressAdjustment", "signature not found")
            return
        }
        // The only caller; the small callee can be inlined into it by the system_server AOT code.
        val caller = AUDIO_SERVICE.toClassOrNull()?.let(::resolveAdjustSuggestedStreamVolume)
        if (caller == null) {
            DebugLog.w(TAG, "adjustSuggestedStreamVolume not recognized; an AOT inline may bypass the hook")
        } else if (!deoptimize(caller)) {
            DebugLog.w(TAG, "could not deoptimize ${caller.name}; an AOT inline may bypass the hook")
        }
        deoptimize(suppress)
        suppress.hook("volume_first_press_suppress_adjustment") {
            after { param ->
                HookFailurePolicy.open(TAG, "suppressAdjustment", Unit) {
                    if (!enabled || param.result != true) return@open
                    val stream = param.args.getOrNull(0) as? Int ?: return@open
                    val isMuteAdjust = param.args.getOrNull(2) as? Boolean ?: return@open
                    if (stream == AudioManager.STREAM_MUSIC && !isMuteAdjust) param.result = false
                }
            }
        }
        DebugLog.d(TAG, "hooked suppressAdjustment enabled=$enabled")
    }

    fun refreshSettings() {
        val next = settingEnabled
        if (next != enabled) DebugLog.i(TAG, "first media volume-key press adjusts=$next")
        enabled = next
    }

    private fun resolveAdjustSuggestedStreamVolume(service: Class<*>): Method? {
        val int = Int::class.javaPrimitiveType!!
        val signature = arrayOf(
            int, int, int, String::class.java, String::class.java, int, int, Boolean::class.javaPrimitiveType!!, int
        )
        return service.declaredMethods.singleOrNull {
            it.name == "adjustSuggestedStreamVolume" && !Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(signature)
        }
    }
}
