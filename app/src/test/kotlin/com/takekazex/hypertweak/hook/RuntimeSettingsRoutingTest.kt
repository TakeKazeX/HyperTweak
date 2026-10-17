package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.dock.DockConfig
import org.junit.Assert.*
import org.junit.Test

class RuntimeSettingsRoutingTest {
    @Test fun everyExistingLiveRouteHasExplicitConsumersIncludingScreenshotEditorAndSystemServer() {
        assertEquals(setOf(Groups.SCREENSHOT), Groups.forProcess("com.miui.screenshot"))
        assertEquals(Groups.forProcess("com.miui.screenshot"), Groups.forProcess("com.miui.screenshot:photo_editor"))
        assertEquals(setOf(Groups.BACK, Groups.DOCK, Groups.VOLUME), Groups.forProcess("system_server", true))
        assertEquals(setOf(Groups.XMSF), Groups.forProcess("com.xiaomi.xmsf:push"))
        assertTrue(Groups.forProcess("com.miui.home").contains(Groups.NATIVE))
        assertTrue(Groups.forProcess("unrelated.package").isEmpty())
    }
    @Test fun eachDestinationReceivesOnlyItsCompleteRegisteredSubset() {
        val values = mapOf<String, Any>(Preferences.KEY_THREE_ISLANDS to true,
            Preferences.KEY_SCREENSHOT_AUTO_FRAME to true, Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE to 1,
            Preferences.KEY_BACK_GESTURE_ENABLED to true, Preferences.KEY_VOLUME_KEY_STEP_COUNT to 50,
            DockConfig.KEY to DockConfig(enabled = true).encode(), "icon_duo_enabled" to true)
        val screenshot = Groups.subset("com.miui.screenshot", values)
        assertEquals(setOf(Preferences.KEY_SCREENSHOT_AUTO_FRAME, Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE), screenshot.keys)
        val server = Groups.subset("android", values)
        assertTrue(server.containsKey(DockConfig.KEY))
        assertTrue(server.containsKey(Preferences.KEY_BACK_GESTURE_ENABLED))
        assertFalse(server.containsKey(Preferences.KEY_THREE_ISLANDS))
        assertFalse(server.containsKey("icon_duo_enabled"))
    }
    @Test fun oldGenerationSnapshotDoesNotInventDefaultsForNewlyMigratedDomains() {
        val legacy = arrayOf<Any>(11L, hashMapOf("icon_duo_enabled" to true))
        val ledger = RuntimeSettingsLedger().also { it.restore(legacy) }
        assertTrue(ledger.owns("icon_duo_enabled"))
        assertFalse(ledger.owns(Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON))
        assertFalse(ledger.owns(Preferences.KEY_SCREENSHOT_AUTO_FRAME))
        assertFalse(ledger.owns(Preferences.KEY_BACK_GESTURE_ENABLED))
        val updated = RuntimeSettingsSnapshot.read(12, mapOf(Preferences.KEY_SCREENSHOT_AUTO_FRAME to true))!!
        assertTrue(ledger.accept(updated))
        assertTrue(ledger.owns(Preferences.KEY_SCREENSHOT_AUTO_FRAME))
        assertEquals(true, ledger.value(Preferences.KEY_SCREENSHOT_AUTO_FRAME))
    }
    @Test fun processScopedSnapshotsSurviveReplacementWithoutClaimingOtherDomains() {
        val screenshot = RuntimeSettingsSnapshot.read(20, mapOf(Preferences.KEY_SCREENSHOT_AUTO_FRAME to true), setOf(Groups.SCREENSHOT))!!
        val old = RuntimeSettingsLedger().also { it.accept(screenshot) }
        val current = RuntimeSettingsLedger().also { it.restore(old.save()) }
        assertTrue(current.owns(Preferences.KEY_SCREENSHOT_AUTO_FRAME))
        assertFalse(current.owns(Preferences.KEY_THREE_ISLANDS))
        assertFalse(current.accept(screenshot.copy(revision = 19)))
        val reset = RuntimeSettingsSnapshot.read(21, emptyMap<String, Any>(), setOf(Groups.SCREENSHOT))!!
        assertEquals(setOf(Groups.SCREENSHOT), current.changedGroups(reset))
        assertTrue(current.accept(reset))
        assertTrue(current.owns(Preferences.KEY_SCREENSHOT_AUTO_FRAME))
        assertNull(current.value(Preferences.KEY_SCREENSHOT_AUTO_FRAME))
    }
    @Test fun newlyMigratedGroupsAreAppliedEvenWhenTheirCommittedValuesAreAllDefaults() {
        val legacy = RuntimeSettingsLedger().also { it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true))) }
        val current = RuntimeSettingsSnapshot.read(2L, mapOf("icon_duo_enabled" to true))!!
        assertTrue(legacy.changedGroups(current).containsAll(setOf(Groups.NATIVE, Groups.BACK, Groups.SCREENSHOT)))
        val screenshot = RuntimeSettingsSnapshot.read(3L, emptyMap<String, Any>(), setOf(Groups.SCREENSHOT))!!
        assertNull(screenshot.mutate("icon_duo_enabled", false, 4L))
    }
    @Test fun nativePayloadIdentityAndValuesSurviveTheUnifiedSnapshotAndGenerationHandoff() {
        val native = NativeRuleStateSnapshot(true, 5, true, 23L, 2L, true)
        val snapshot = RuntimeSettingsSnapshot.read(41L, native.settingsValues(), setOf(Groups.NATIVE))!!
        val old = RuntimeSettingsLedger().also { it.accept(snapshot) }
        val restored = RuntimeSettingsLedger().also { it.restore(old.save()) }
        assertEquals(native, NativeRuleStateSnapshot.read { restored.latest()!!.values })
        assertEquals(setOf(Groups.NATIVE), restored.latest()!!.groups)
        assertFalse(restored.owns(Preferences.KEY_BACK_GESTURE_ENABLED))
    }
    @Test fun invalidScreenshotNativeBackAndDockConfigurationsAreRejectedBeforePublication() {
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE to 2)))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(Preferences.KEY_SCREENSHOT_AUTO_FRAME to "true")))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(Preferences.KEY_OPENED_FOLDER_COLUMNS to 6)))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(Preferences.KEY_BACK_GESTURE_ENABLED to "true")))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(DockConfig.KEY to "corrupt")))
        assertNull(RuntimeSettingsSnapshot.read(1, emptyMap<String, Any>(), setOf("unregistered")))
    }
    @Test fun commonAcknowledgedMutationPreservesOtherDomainsWhileResetReplacesThemTogether() {
        val original = RuntimeSettingsSnapshot.read(1, mapOf(Preferences.KEY_SCREENSHOT_AUTO_FRAME to true,
            Preferences.KEY_BACK_GESTURE_ENABLED to true, DockConfig.KEY to DockConfig(enabled = true).encode()))!!
        val next = original.mutate(Preferences.KEY_VOLUME_KEY_STEP_COUNT, 30, 2)!!
        assertEquals(true, next.values[Preferences.KEY_SCREENSHOT_AUTO_FRAME])
        assertEquals(true, next.values[Preferences.KEY_BACK_GESTURE_ENABLED])
        assertEquals(setOf(Groups.VOLUME), Groups.changed(original.values, next.values))
        assertEquals(setOf(Groups.SCREENSHOT, Groups.BACK, Groups.DOCK, Groups.VOLUME),
            Groups.changed(next.values, emptyMap()))
    }
}
