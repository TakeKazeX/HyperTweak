package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.Preferences

/**
 * The pure decision behind [PowerSaveOverrideHooker]: does this Security Center write belong to a
 * power-save pin, and if so, does the user want that feature kept?
 *
 * Security Center pins several unrelated features while 省电模式 is active. Each pin is a plain
 * Settings write of one known value to one known key, plus a `power_center_*` backup the host uses
 * to restore the user's value when power save is left. That shape is the same for every feature, so
 * one policy covers them all; keeping it separate from the hook makes the key/value contract
 * unit-testable and keeps the accepted keys and values in one auditable place.
 *
 * Stored state lives in [Preferences] (`powerSaveOverride*`), so the settings UI does not need this
 * internal object. The evidence for each value is in `docs/FEATURE_DETAIL.md` (and the
 * reverse-engineering scratch `POWER_SAVE_INVENTORY.md`).
 */
internal object PowerSaveOverridePolicy {

    /** One independently switchable feature that HyperOS pins during power save. */
    enum class Group {
        /** 自适应刷新率 → `nh.h.k()` / `yh.x.I()` pin the panel to 60 Hz. */
        REFRESH_RATE,

        /** 触感反馈 → `nh.f` turns `haptic_feedback_enabled` off. */
        HAPTIC_FEEDBACK,

        /** 系统音效 → `nh.i` silences five System sound switches. */
        SYSTEM_SOUNDS,

        /** 唤醒方式 → `nh.k` disables pickup and notification wakeup. */
        WAKEUP_GESTURES,
    }

    private class Pin(val value: Int, val group: Group)

    /**
     * key → the exact value the power-save pin writes, and the feature that owns it.
     *
     * Only these key/value pairs are ever dropped, so unrelated Settings traffic in the same process
     * passes through untouched. The key set is intentionally literal: a new ROM pin has to be added
     * here deliberately rather than being swept up by a pattern.
     */
    private val PINS: Map<String, Pin> = buildMap {
        // 低帧率省电: DisplayModeDirectorImpl.getMiuiRefreshRateRange() reads these into
        // PRIORITY_MIUI_REFRESH_RATE.
        pin("user_refresh_rate", 60, Group.REFRESH_RATE)
        pin("peak_refresh_rate", 60, Group.REFRESH_RATE)
        pin("miui_refresh_rate", 60, Group.REFRESH_RATE)

        // 触感反馈: nh.f writes 0 and backs the user value up in power_center_haptic_feed_back_mode.
        pin("haptic_feedback_enabled", 0, Group.HAPTIC_FEEDBACK)

        // 系统音效: nh.i writes 0 for each and backs up via power_center_sound_mode_*.
        pin("dtmf_tone", 0, Group.SYSTEM_SOUNDS)
        pin("sound_effects_enabled", 0, Group.SYSTEM_SOUNDS)
        pin("lockscreen_sounds_enabled", 0, Group.SYSTEM_SOUNDS)
        pin("has_screenshot_sound", 0, Group.SYSTEM_SOUNDS)
        pin("delete_sound_effect", 0, Group.SYSTEM_SOUNDS)

        // 唤醒方式: nh.k writes 0 for pickup/notification wakeup and backs up via
        // power_center_wakeup_*. 双击唤醒 (`gesture_wakeup`) is read but never pinned on this build,
        // so it is intentionally absent.
        pin("pick_up_gesture_wakeup_mode", 0, Group.WAKEUP_GESTURES)
        pin("wakeup_for_keyguard_notification", 0, Group.WAKEUP_GESTURES)
    }

    /** The Settings keys this policy can intercept. */
    val keys: Set<String> = PINS.keys

    /** The value the power-save pin writes for each interceptable key. */
    val pinnedValues: Map<String, Int> = PINS.mapValues { it.value.value }

    /**
     * The feature that owns this write, or null when it is not a power-save pin at all. Callers then
     * decide with [isGroupEnabled] whether to drop it.
     */
    fun groupOf(key: String, value: Int): Group? = PINS[key]?.takeIf { it.value == value }?.group

    /** Reads the independent feature switch, including legacy configuration compatibility. */
    fun isGroupEnabled(group: Group): Boolean =
        when (group) {
            Group.REFRESH_RATE -> Preferences.keepRefreshRateInPowerSave()
            Group.HAPTIC_FEEDBACK -> Preferences.keepHapticFeedbackInPowerSave()
            Group.SYSTEM_SOUNDS -> Preferences.keepSystemSoundsInPowerSave()
            Group.WAKEUP_GESTURES -> Preferences.keepWakeupGesturesInPowerSave()
        }

    /** Whether any feature still needs the hook installed. */
    fun anyGroupEnabled(): Boolean = Group.entries.any(::isGroupEnabled)

    private fun MutableMap<String, Pin>.pin(key: String, value: Int, group: Group) {
        put(key, Pin(value, group))
    }
}
