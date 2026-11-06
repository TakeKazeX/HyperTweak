package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.annotation.SuppressLint
import android.media.AudioManager
import android.widget.SeekBar
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.settings.contract.VolumeKeyStepPolicy
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.ArrayDeque

/** The plugin handles keys by setting an absolute media index, bypassing AudioService key flags. */
class ControlCenterVolumeKeyHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingVolumeKeyStepCount by hookSetting(PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT) {
        Preferences.getInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT)
    }
    private val settingVolumeKeyStepScope by hookSetting(PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY) {
        Preferences.getInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE, PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY)
    }

    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_ENABLED, false)
    }

    private companion object {
        // Hidden AudioManager flag consumed by AudioService's actual volume-key path.
        const val FLAG_FROM_KEY = 0x1000
    }

    private data class Frame(val controller: Any, val oldStep: Int?)
    private val frames = ThreadLocal<ArrayDeque<Frame>>()

    override fun onHook() {
        val target = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController".toClassOrNull()
            ?: return
        val change = HostAccess.requireDeclaredMethod(target, "changeVolume", Boolean::class.javaPrimitiveType!!, accessible = false)
        val step = HostAccess.requireDeclaredField(target, "sliderKeyStep", accessible = true)
        val minimum = HostAccess.requireDeclaredField(target, "streamMinVolume", accessible = true)
        val maximum = HostAccess.requireDeclaredField(target, "streamMaxVolume", accessible = true)
        val remote = HostAccess.requireDeclaredField(target, "miplayDeviceType", accessible = true)
        val tracking = HostAccess.requireDeclaredField(target, "tracking", accessible = true)
        val slider = HostAccess.requireDeclaredMethod(target, "getSlider", accessible = true)
        val targetValue = HostAccess.requireDeclaredMethod(target, "getTargetValue", accessible = true)
        val valueToVolume = HostAccess.requireDeclaredMethod(target, "valueToVolume", Int::class.javaPrimitiveType!!, accessible = true)
        val volumeToValue = HostAccess.requireDeclaredMethod(target, "volumeToValue", Int::class.javaPrimitiveType!!, accessible = true)
        deoptimize(change)
        change.hook("control_center_configured_volume_key_step") {
            before { param ->
                val controller = param.thisObject
                var oldStep: Int? = null
                HookFailurePolicy.open("ControlCenterVolumeKey", "changeVolume", Unit) {
                    // Remote MiPlay devices own their volume range. Dragging keeps its native path.
                    if (!settingEnabled || remote.getInt(controller) != -1 || tracking.getBoolean(controller) ||
                        slider.invoke(controller) !is SeekBar) return@open
                    val increase = param.args.firstOrNull() as? Boolean ?: return@open
                    val configuration = VolumeKeyStepPolicy.configuration(
                        settingVolumeKeyStepCount,
                        settingVolumeKeyStepScope, settingEnabled
                    )
                    val currentValue = targetValue.invoke(controller) as? Int ?: return@open
                    val current = valueToVolume.invoke(controller, currentValue) as? Int ?: return@open
                    val next = VolumeKeyStepPolicy.nextIndex(current, minimum.getInt(controller),
                        maximum.getInt(controller), configuration.steps, if (increase) 1 else -1)
                    val nextValue = volumeToValue.invoke(controller, next) as? Int ?: return@open
                    oldStep = step.getInt(controller)
                    // Use the host converters, including its special zero/maximum mapping.
                    // Keep native animation, feedback, delayed writes and sleep-low-volume rules.
                    step.setInt(controller, if (increase) nextValue - currentValue else currentValue - nextValue)
                }
                val stack = frames.get() ?: ArrayDeque<Frame>().also(frames::set)
                stack.addLast(Frame(controller, oldStep))
            }
            after {
                val stack = frames.get()
                val frame = stack?.pollLast()
                if (stack?.isEmpty() == true) frames.remove()
                if (frame?.oldStep != null) {
                    HookFailurePolicy.open("ControlCenterVolumeKey", "restore key step", Unit) {
                        step.setInt(frame.controller, frame.oldStep)
                    }
                }
            }
        }
        DebugLog.hookRegistered("ControlCenterVolumeKey", "VolumeSliderController.changeVolume(boolean)")
        // The media detail header uses a separate local controller. Mark its actual adjustment
        // as key input so it shares AudioService's configured step; other MiPlay controllers stay native.
        HookFailurePolicy.open("ControlCenterVolumeKey", "media detail registration", Unit) {
            val local = "com.android.systemui.SystemVolumeController".toClassOrNull() ?: return@open
            val adjust = HostAccess.requireDeclaredMethod(local, "doAdjustVolume", Boolean::class.javaPrimitiveType!!, accessible = false)
            val audio = HostAccess.requireDeclaredMethod(local, "getMAudioManager", accessible = true)
            deoptimize(adjust)
            adjust.hook("control_center_media_detail_volume_key") {
                before { param ->
                    HookFailurePolicy.open("ControlCenterVolumeKey", "media detail adjustment", Unit) {
                        if (!settingEnabled) return@open
                        val increase = param.args.firstOrNull() as? Boolean ?: return@open
                        val manager = audio.invoke(param.thisObject) as? AudioManager ?: return@open
                        adjustMediaVolumeFromKey(manager, increase)
                        param.result = null
                    }
                }
            }
        }

    }

    // The public SDK's flag IntDef omits FLAG_FROM_KEY; this SystemUI hook deliberately uses
    // the platform flag so the downstream AudioService hook applies the configured relative step.
    @SuppressLint("WrongConstant")
    private fun adjustMediaVolumeFromKey(manager: AudioManager, increase: Boolean) {
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, FLAG_FROM_KEY)
    }

}
