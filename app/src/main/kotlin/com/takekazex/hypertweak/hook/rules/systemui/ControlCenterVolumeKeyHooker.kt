package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.annotation.SuppressLint
import android.media.AudioManager
import android.widget.SeekBar
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.rules.system.VolumeKeyStepPolicy
import com.takekazex.hypertweak.hook.rules.system.VolumeKeyStepSequence
import com.takekazex.hypertweak.util.DebugLog
import java.util.ArrayDeque
import java.util.WeakHashMap

/** The plugin handles keys by setting an absolute media index, bypassing AudioService key flags. */
class ControlCenterVolumeKeyHooker : DynamicHooker() {
    private companion object {
        // Hidden AudioManager flag consumed by AudioService's actual volume-key path.
        const val FLAG_FROM_KEY = 0x1000
    }

    private data class Frame(val controller: Any, val oldStep: Int?)
    private val frames = ThreadLocal<ArrayDeque<Frame>>()
    private val sequences = WeakHashMap<Any, VolumeKeyStepSequence>()

    override fun onHook() {
        val target = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController".toClassOrNull()
            ?: return
        val change = target.getDeclaredMethod("changeVolume", Boolean::class.javaPrimitiveType!!)
        val step = target.getDeclaredField("sliderKeyStep").apply { isAccessible = true }
        val minimum = target.getDeclaredField("streamMinVolume").apply { isAccessible = true }
        val maximum = target.getDeclaredField("streamMaxVolume").apply { isAccessible = true }
        val remote = target.getDeclaredField("miplayDeviceType").apply { isAccessible = true }
        val tracking = target.getDeclaredField("tracking").apply { isAccessible = true }
        val slider = target.getDeclaredMethod("getSlider").apply { isAccessible = true }
        deoptimize(change)
        change.hook("control_center_configured_volume_key_step") {
            before { param ->
                val controller = param.thisObject
                var oldStep: Int? = null
                HookFailurePolicy.open("ControlCenterVolumeKey", "changeVolume", Unit) {
                    // Remote MiPlay devices own their volume range. Dragging keeps its native path.
                    if (remote.getInt(controller) != -1 || tracking.getBoolean(controller) ||
                        slider.invoke(controller) !is SeekBar) return@open
                    val increase = param.args.firstOrNull() as? Boolean ?: return@open
                    val configuration = VolumeKeyStepPolicy.configuration(
                        Preferences.getInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT),
                        Preferences.getInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE, PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY)
                    )
                    val range = maximum.getInt(controller) - minimum.getInt(controller)
                    if (range <= 0) return@open
                    val delta = synchronized(sequences) {
                        sequences.getOrPut(controller) { VolumeKeyStepSequence() }
                            .next(range, configuration, if (increase) 1 else -1)
                    }
                    oldStep = step.getInt(controller)
                    // Native slider values use 1000 units per public volume index. Override only
                    // the key call so native animation, haptics, mute and sleep-low-volume remain.
                    step.setInt(controller, delta * 1000)
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
            val adjust = local.getDeclaredMethod("doAdjustVolume", Boolean::class.javaPrimitiveType!!)
            val audio = local.getDeclaredMethod("getMAudioManager").apply { isAccessible = true }
            deoptimize(adjust)
            adjust.hook("control_center_media_detail_volume_key") {
                before { param ->
                    HookFailurePolicy.open("ControlCenterVolumeKey", "media detail adjustment", Unit) {
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
    // the platform flag so the downstream AudioService hook applies the configured key step.
    @SuppressLint("WrongConstant")
    private fun adjustMediaVolumeFromKey(manager: AudioManager, increase: Boolean) {
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, FLAG_FROM_KEY)
    }

    override fun onPrepareHotReload() {
        synchronized(sequences) { sequences.clear() }
    }
}
