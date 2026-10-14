package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test

class IconSettingsSnapshotTest {
    @Test fun completeSnapshotOwnsMissingKeysSoOldRemoteValuesCannotReturn() {
        val ledger = IconSettingsLedger()
        assertTrue(ledger.accept(IconSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to true))!!))
        assertEquals(true, ledger.value("icon_duo_enabled"))
        assertTrue(ledger.accept(IconSettingsSnapshot.read(2, emptyMap<String, Any>())!!))
        assertTrue(ledger.owns("icon_duo_enabled"))
        assertNull(ledger.value("icon_duo_enabled"))
        assertFalse(ledger.owns("hide_recents_clear"))
    }
    @Test fun newerSnapshotsSurviveReloadWhileStaleAndConflictingRepliesAreRejected() {
        val current = IconSettingsSnapshot.read(8, mapOf("icon_stacked_enabled" to true,
            "systemui_cc_network_first_row" to false))!!
        val old = IconSettingsLedger().also { assertTrue(it.accept(current)) }
        val restored = IconSettingsLedger().also { it.restore(old.save()) }
        assertFalse(restored.accept(IconSettingsSnapshot.read(7, mapOf("icon_stacked_enabled" to false))!!))
        assertFalse(restored.accept(IconSettingsSnapshot.read(8, mapOf("icon_stacked_enabled" to false))!!))
        assertEquals(true, restored.value("icon_stacked_enabled"))
        assertTrue(restored.accept(current.mutate("systemui_cc_network_first_row", true, 9)!!))
        assertEquals(true, restored.value("systemui_cc_network_first_row"))
    }
    @Test fun acknowledgedMutationDoesNotImportRejectedEditorShadowOrUnrelatedSettings() {
        val baseline = IconSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to true,
            "hide_recents_clear" to true, "icon_tuner_position_val" to setOf("0:wifi", "1:mobile")))!!
        val rejectedShadow = baseline.mutate("icon_duo_enabled", false, 2)!!
        val next = baseline.mutate("icon_stacked_enabled", true, 3)!!
        assertEquals(false, rejectedShadow.values["icon_duo_enabled"])
        assertEquals(true, next.values["icon_duo_enabled"])
        assertEquals(true, next.values["icon_stacked_enabled"])
        assertFalse(next.values.containsKey("hide_recents_clear"))
        assertEquals(setOf("0:wifi", "1:mobile"), next.values["icon_tuner_position_val"])
    }
    @Test fun malformedIncompleteAndOversizedValuesAreRejected() {
        assertNull(IconSettingsSnapshot.read(0, emptyMap<String, Any>()))
        assertNull(IconSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to Any())))
        assertNull(IconSettingsSnapshot.read(1, mapOf("icon_duo_size" to Float.NaN)))
        assertNull(IconSettingsSnapshot.read(1, mapOf("icon_tuner_position_val" to setOf(1))))
        assertNull(IconSettingsSnapshot.read(1, mapOf("icon_tuner_stacked_icon_svg_single" to "x".repeat(131073))))
        val strings = mutableSetOf("wifi")
        val snapshot = IconSettingsSnapshot.read(1, mapOf("icon_tuner_position_val" to strings))!!
        strings += "mobile"
        assertEquals(setOf("wifi"), snapshot.values["icon_tuner_position_val"])
    }
}
