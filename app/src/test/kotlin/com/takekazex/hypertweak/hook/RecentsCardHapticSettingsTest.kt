package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.nativebridge.NativeRuleMutation
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.nativebridge.NativeSnapshotLedger
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import org.junit.Assert.*
import org.junit.Test

class RecentsCardHapticSettingsTest {
    @Test fun `toggle preserves acknowledged sibling choices and resets to off`() {
        val before = NativeRuleStateSnapshot(true, 5, true, 10, 2, assistantWidgets = true)
        val enabled = NativeRuleMutation.apply(before, PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, true, 11)
        assertEquals(before.copy(revision = 11, recentsHaptic = true), enabled)
        assertEquals(enabled, NativeRuleStateSnapshot.read { enabled.settingsValues() })
        assertFalse(NativeRuleStateSnapshot(false, 3, false, 12, 3).recentsHaptic)
        assertNull(NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to 1) })
    }

    @Test fun `replacement preserves the switch and accepts the old six element ledger`() {
        val snapshot = NativeRuleStateSnapshot(false, 4, false, 10, 2, recentsHaptic = true)
        val ledger = NativeSnapshotLedger()
        assertTrue(ledger.offer(snapshot))
        val restored = NativeSnapshotLedger()
        restored.restore(ledger.save())
        assertEquals(snapshot, restored.latest())
        assertFalse(restored.offer(snapshot.copy(recentsHaptic = false)))
        val legacy = NativeSnapshotLedger()
        legacy.restore(arrayOf<Any>(false, 4, false, 10L, 2L, true))
        assertEquals(snapshot.copy(recentsHaptic = false, assistantWidgets = true), legacy.latest())
    }

    @Test fun `shared live channel routes the preference to native launcher and SystemUI`() {
        val key = PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC
        assertEquals(setOf(RuntimeSettingsGroups.NATIVE), RuntimeSettingsGroups.owners(key))
        assertTrue(RuntimeSettingsGroups.owns("com.miui.home", key))
        assertTrue(RuntimeSettingsGroups.owns("com.android.systemui", key))
        assertFalse(RuntimeSettingsGroups.owns("android", key))
    }
}
