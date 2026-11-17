package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Logical brightness (0..1, stored in `mi_flashlight_strength`) and torch strength levels. */
internal object FlashlightLevels {
    fun clamp(logic: Float, max: Int): Float = logic.coerceIn(1f / max.coerceAtLeast(1), 1f)

    /** Same conversion as `MiFlashlightManager`: `(int)(max * logic)`, never below 1. */
    fun strength(logic: Float, max: Int): Int =
        (max.coerceAtLeast(1) * clamp(logic, max)).toInt().coerceIn(1, max.coerceAtLeast(1))

    fun logic(strength: Int, max: Int): Float = clamp(strength.toFloat() / max.coerceAtLeast(1), max)

    fun isFlashlightFocus(param: String?): Boolean = param != null && runCatching {
        JSONObject(param).optJSONObject("param_v2")?.optString("business") == "miui_flashlight"
    }.getOrDefault(false)
}

/**
 * The native page's slider rules (`MiFlashlightController`): progress 0..100, moved by the
 * vertical travel over the slider's own length, 0 meaning off.
 */
internal object FlashlightSlider {
    const val MAX = 100

    /** `seekBarOnTouchListener`: start progress plus travel over `seekBarTouchLength`. */
    fun drag(startProgress: Int, travelUp: Float, length: Float): Int =
        (startProgress + (travelUp / length.coerceAtLeast(1f) * MAX).toInt()).coerceIn(0, MAX)

    /** `hapticWhenSeekBarToEdge`: only landing on either end vibrates. */
    fun isEdge(progress: Int): Boolean = progress == 0 || progress == MAX

    /** `updateFlashlightUi`: the renderer's progress, never below 0.15 while lit. */
    fun renderProgress(on: Boolean, shown: Float): Float =
        (shown / MAX).coerceIn(if (on) 0.15f else 0f, 1f)

    /** `updateSeekBarProcess`: at least 5 while lit, empty below 5 while off. */
    fun shown(on: Boolean, progress: Int): Int = when {
        on -> progress.coerceAtLeast(5)
        progress < 5 -> 0
        else -> progress
    }

}

/**
 * Keeps the flashlight island alive in its off state while its expanded panel is on screen.
 *
 * `MiuiFlashlightControllerImpl.handleFocusNotificationOrStatus(on, op, user)` asks the plugin's
 * `ToggleChangeReceiver` to cancel the focus notification (`op 0`) when the torch goes off. While
 * the panel is held, that close is rewritten to `op 3` (status only) and remembered; the panel
 * replays the close once it is released with the torch still off.
 *
 * Switching on asks for a show (`op 1`) or resend (`op 2`) of the notification. While the panel is
 * held the island already exists, and the repost only makes the host re-add the expanded view,
 * whose detach destroys the renderer's surface mid-drag. Those become `op 3` as well.
 */
internal object FlashlightIslandHold {
    const val OP_CANCEL = 0
    const val OP_SHOW = 1
    const val OP_RESEND = 2
    const val OP_STATUS_ONLY = 3

    @Volatile var held = false
    private val suppressed = AtomicBoolean(false)

    fun onNativeStatus(on: Boolean, op: Int): Int {
        if (on) {
            suppressed.set(false)
            return if (held && (op == OP_SHOW || op == OP_RESEND)) OP_STATUS_ONLY else op
        }
        if (op != OP_CANCEL || !held) return op
        suppressed.set(true)
        return OP_STATUS_ONLY
    }

    fun takeSuppressedClose(): Boolean = suppressed.getAndSet(false)

    fun clear() {
        held = false
        suppressed.set(false)
    }
}
