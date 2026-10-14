package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class IconSettingsReloadTransactionTest {
    @Test fun repeatedSettingsChangesRetireOldHandlesAndRestoreBeforeHostRecovery() {
        val events = ArrayList<String>()
        var handles = 2
        repeat(3) {
            IconSettingsReloadTransaction.run(
                listOf("visibility", "carrier"),
                capture = { events += "capture:$it"; "host:$it" },
                prepare = { events += "prepare:$it" },
                reset = { events += "reset:$it" },
                install = { events += "install:$it"; handles++ },
                restore = { hook, snapshot -> assertEquals("host:$hook", snapshot); events += "restore:$hook" },
                retire = { events += "retire"; handles -= 2 },
                recover = { events += "recover" }
            )
            assertEquals(2, handles)
        }
        assertEquals(listOf("capture:visibility", "capture:carrier", "prepare:carrier", "prepare:visibility",
            "retire", "reset:visibility", "reset:carrier", "install:visibility", "install:carrier",
            "restore:visibility", "restore:carrier", "recover"), events.take(12))
    }

    @Test fun oneFailedHookCannotSkipOtherHooksRetirementOrRecovery() {
        val events = ArrayList<String>()
        val failure = IllegalStateException("missing optional target")
        try {
            IconSettingsReloadTransaction.run(listOf(1, 2), capture = { it }, prepare = {}, reset = {},
                install = { events += "install:$it"; if (it == 1) throw failure },
                restore = { hook, _ -> events += "restore:$hook" },
                retire = { events += "retire" }, recover = { events += "recover" })
            fail("Failure must be reported after independent recovery")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(listOf("retire", "install:1", "install:2", "restore:1", "restore:2", "recover"), events)
    }

    @Test fun failedSnapshotLeavesEveryCurrentHookUntouched() {
        var touched = false
        try {
            IconSettingsReloadTransaction.run(listOf(1), capture = { error("snapshot failed") },
                prepare = { touched = true }, reset = { touched = true }, install = { touched = true },
                restore = { _, _ -> touched = true }, retire = { touched = true }, recover = { touched = true })
            fail()
        } catch (_: IllegalStateException) { assertFalse(touched) }
    }

    @Test fun settingsPolicyCoversNestedIconPagesWithoutRefreshingOtherFeatures() {
        listOf("icon_duo_enabled", "icon_tuner_mobile_row_style", "icon_tuner_position_val",
            "systemui_cc_network_first_row", "systemui_cc_icon_row_overrides", "systemui_cc_privacy_offset",
            "statusbar_hide_carrier_one", "systemui_ls_hide_carrier_two", "statusbar_notif_icon_max",
            "statusbar_region_sampling", "prefs_epoch").forEach { assertTrue(it, IconSettingsPreferencePolicy.affectsIcons(it)) }
        assertTrue(IconSettingsPreferencePolicy.affectsIcons(null))
        listOf("systemui_control_center_resize_enabled", "hide_recents_clear", "volume_key_step_count",
            "notification_header_seconds").forEach { assertFalse(it, IconSettingsPreferencePolicy.affectsIcons(it)) }
    }
}
