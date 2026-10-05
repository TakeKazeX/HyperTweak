package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.securitycenter.PowerSaveOverridePolicy.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every power-save pin is a specific key/value pair: dropping the wrong value (a real user choice)
 * or the wrong key (another feature in the same process) would silently break unrelated Settings.
 * The reverse-engineering inventory is `POWER_SAVE_INVENTORY.md`; these cases pin the contract.
 */
class PowerSaveOverridePolicyTest {

    @Test
    fun `maps each pinned key to its feature and value`() {
        assertEquals(Group.REFRESH_RATE, PowerSaveOverridePolicy.groupOf("user_refresh_rate", 60))
        assertEquals(Group.REFRESH_RATE, PowerSaveOverridePolicy.groupOf("peak_refresh_rate", 60))
        assertEquals(Group.REFRESH_RATE, PowerSaveOverridePolicy.groupOf("miui_refresh_rate", 60))

        assertEquals(Group.HAPTIC_FEEDBACK, PowerSaveOverridePolicy.groupOf("haptic_feedback_enabled", 0))

        assertEquals(Group.SYSTEM_SOUNDS, PowerSaveOverridePolicy.groupOf("dtmf_tone", 0))
        assertEquals(Group.SYSTEM_SOUNDS, PowerSaveOverridePolicy.groupOf("sound_effects_enabled", 0))
        assertEquals(Group.SYSTEM_SOUNDS, PowerSaveOverridePolicy.groupOf("lockscreen_sounds_enabled", 0))
        assertEquals(Group.SYSTEM_SOUNDS, PowerSaveOverridePolicy.groupOf("has_screenshot_sound", 0))
        assertEquals(Group.SYSTEM_SOUNDS, PowerSaveOverridePolicy.groupOf("delete_sound_effect", 0))

        assertEquals(Group.WAKEUP_GESTURES, PowerSaveOverridePolicy.groupOf("pick_up_gesture_wakeup_mode", 0))
        assertEquals(Group.WAKEUP_GESTURES, PowerSaveOverridePolicy.groupOf("wakeup_for_keyguard_notification", 0))
    }

    @Test
    fun `ignores the opposite value on a pinned key`() {
        // Turning a feature back ON must always reach the framework, whatever the module switch says.
        assertNull(PowerSaveOverridePolicy.groupOf("haptic_feedback_enabled", 1))
        assertNull(PowerSaveOverridePolicy.groupOf("dtmf_tone", 1))
        assertNull(PowerSaveOverridePolicy.groupOf("lockscreen_sounds_enabled", 1))
        assertNull(PowerSaveOverridePolicy.groupOf("pick_up_gesture_wakeup_mode", 1))
        assertNull(PowerSaveOverridePolicy.groupOf("wakeup_for_keyguard_notification", 1))
        assertNull(PowerSaveOverridePolicy.groupOf("user_refresh_rate", 120))
        assertNull(PowerSaveOverridePolicy.groupOf("miui_refresh_rate", 90))
        assertNull(PowerSaveOverridePolicy.groupOf("peak_refresh_rate", 0))
    }

    @Test
    fun `ignores unrelated and neighbouring settings`() {
        // The host's own backup keys and the power-save master switch must pass, as must ordinary
        // Settings traffic that happens to share the 60/0 value.
        listOf(
            "power_center_haptic_feed_back_mode",
            "power_center_sound_mode_click",
            "power_center_wakeup_pickup",
            "power_center_finger_aod",
            "POWER_SAVE_MODE_OPEN",
            "screen_brightness",
            "is_smart_fps",
            "gesture_wakeup", // read by nh.k but never pinned on this build
        ).forEach { key ->
            assertNull("unrelated key $key must pass through", PowerSaveOverridePolicy.groupOf(key, 0))
        }
    }

    @Test
    fun `exposes exactly the pinned key set`() {
        assertEquals(
            setOf(
                "user_refresh_rate", "peak_refresh_rate", "miui_refresh_rate",
                "haptic_feedback_enabled",
                "dtmf_tone", "sound_effects_enabled", "lockscreen_sounds_enabled",
                "has_screenshot_sound", "delete_sound_effect",
                "pick_up_gesture_wakeup_mode", "wakeup_for_keyguard_notification",
            ),
            PowerSaveOverridePolicy.keys,
        )
    }

    @Test
    fun `every group owns at least one pinned key`() {
        // A group with no pin would be a switch that silently does nothing.
        val owned = PowerSaveOverridePolicy.pinnedValues.mapNotNull { (key, value) ->
            PowerSaveOverridePolicy.groupOf(key, value)
        }.toSet()
        assertEquals(Group.entries.toSet(), owned)
    }
}
