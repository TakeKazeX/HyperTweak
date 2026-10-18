package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import org.junit.Assert.*
import org.junit.Test

class RuntimeSettingsSnapshotTest {
    @Test fun completeSnapshotOwnsMissingKeysSoOldRemoteValuesCannotReturn() {
        val ledger = RuntimeSettingsLedger()
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to true))!!))
        assertEquals(true, ledger.value("icon_duo_enabled"))
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(2, emptyMap<String, Any>())!!))
        assertTrue(ledger.owns("icon_duo_enabled"))
        assertNull(ledger.value("icon_duo_enabled"))
        assertFalse(ledger.owns("unregistered_setting"))
    }
    @Test fun newerSnapshotsSurviveReloadWhileStaleAndConflictingRepliesAreRejected() {
        val current = RuntimeSettingsSnapshot.read(8, mapOf("icon_stacked_enabled" to true,
            "systemui_cc_network_first_row" to false))!!
        val old = RuntimeSettingsLedger().also { assertTrue(it.accept(current)) }
        val restored = RuntimeSettingsLedger().also { it.restore(old.save()) }
        assertFalse(restored.accept(RuntimeSettingsSnapshot.read(7, mapOf("icon_stacked_enabled" to false))!!))
        assertFalse(restored.accept(RuntimeSettingsSnapshot.read(8, mapOf("icon_stacked_enabled" to false))!!))
        assertEquals(true, restored.value("icon_stacked_enabled"))
        assertTrue(restored.accept(current.mutate("systemui_cc_network_first_row", true, 9)!!))
        assertEquals(true, restored.value("systemui_cc_network_first_row"))
    }
    @Test fun acknowledgedMutationDoesNotImportRejectedEditorShadowOrUnrelatedSettings() {
        val baseline = RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to true,
            "unregistered_setting" to true, "icon_tuner_position_val" to setOf("0:wifi", "1:mobile")))!!
        val rejectedShadow = baseline.mutate("icon_duo_enabled", false, 2)!!
        val next = baseline.mutate("icon_stacked_enabled", true, 3)!!
        assertEquals(false, rejectedShadow.values["icon_duo_enabled"])
        assertEquals(true, next.values["icon_duo_enabled"])
        assertEquals(true, next.values["icon_stacked_enabled"])
        assertFalse(next.values.containsKey("unregistered_setting"))
        assertEquals(setOf("0:wifi", "1:mobile"), next.values["icon_tuner_position_val"])
    }
    @Test fun islandCommitsReachTheSameLedgerAndResetCannotResurrectRemoteTrue() {
        val ledger = RuntimeSettingsLedger()
        val enabled = RuntimeSettingsSnapshot.read(1, mapOf(Preferences.KEY_THREE_ISLANDS to true,
            Preferences.KEY_ISLAND_NARROW_TIME_FONT to true))!!
        assertTrue(ledger.accept(enabled))
        val disabled = enabled.mutate(Preferences.KEY_THREE_ISLANDS, false, 2)!!
        assertEquals(setOf(RuntimeSettingsGroups.ISLANDS), ledger.changedGroups(disabled))
        assertTrue(ledger.accept(disabled))
        assertEquals(false, ledger.value(Preferences.KEY_THREE_ISLANDS))
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>())!!))
        assertTrue(ledger.owns(Preferences.KEY_THREE_ISLANDS))
        assertNull(ledger.value(Preferences.KEY_THREE_ISLANDS))
        assertFalse(ledger.accept(enabled))
    }
    @Test fun settingRegistryRoutesIslandKeysWithoutUnrelatedIconRebuilds() {
        listOf(Preferences.KEY_REMOVE_ISLAND_PLACEHOLDER, Preferences.KEY_THREE_ISLANDS, Preferences.KEY_LANDSCAPE_FULL_ISLANDS,
            Preferences.KEY_LANDSCAPE_MORE_ISLANDS, Preferences.KEY_CC_HIDE_SUPER_ISLAND,
            Preferences.KEY_ISLAND_NARROW_TIME_FONT, Preferences.KEY_ISLAND_LIVE_UPDATE_ICON,
            Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST,
            Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST).forEach { key ->
            assertTrue(key, RuntimeSettingsSnapshot.owns(key))
            assertEquals(key, setOf(RuntimeSettingsGroups.ISLANDS), RuntimeSettingsGroups.affected(key))
        }
        assertEquals(setOf(RuntimeSettingsGroups.ICONS), RuntimeSettingsGroups.affected("icon_duo_enabled"))
        assertTrue(RuntimeSettingsGroups.affected("unrelated").isEmpty())
        assertEquals(8, RuntimeSettingsGroups.affected(null).size)
        assertTrue(RuntimeSettingsGroups.affected(null).containsAll(setOf(RuntimeSettingsGroups.ICONS, RuntimeSettingsGroups.ISLANDS)))
    }
    @Test fun malformedIncompleteAndOversizedValuesAreRejected() {
        assertNull(RuntimeSettingsSnapshot.read(0, emptyMap<String, Any>()))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to Any())))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_size" to Float.NaN)))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf("icon_tuner_position_val" to setOf(1))))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf("icon_tuner_stacked_icon_svg_single" to "x".repeat(131073))))
        val strings = mutableSetOf("wifi")
        val snapshot = RuntimeSettingsSnapshot.read(1, mapOf("icon_tuner_position_val" to strings))!!
        strings += "mobile"
        assertEquals(setOf("wifi"), snapshot.values["icon_tuner_position_val"])
    }
}
