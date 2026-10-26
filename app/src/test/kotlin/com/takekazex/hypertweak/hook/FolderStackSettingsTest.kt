package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.nativebridge.NativeRuleMutation
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.nativebridge.NativeSnapshotLedger
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import org.junit.Assert.*
import org.junit.Test
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups

class FolderStackSettingsTest {
    @Test fun stackChangeAndResetOnlyReloadNativeDomain() {
        val old = mapOf<String, Any>(PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW to false, "icon_duo_enabled" to true)
        val enabled = old + (PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW to true)
        assertEquals(setOf(Groups.NATIVE), Groups.changed(old, enabled))
        assertEquals(setOf(Groups.NATIVE), Groups.changed(enabled, old + ("prefs_epoch" to 2L)))
        assertTrue(Groups.changed(enabled, enabled + (PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to 42L)).isEmpty())
    }
    @Test fun snapshotMutationAndLegacyRestore() {
        val initial = NativeRuleStateSnapshot(false, 4, false, 10, 2)
        val next = NativeRuleMutation.apply(initial, PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, true, 11)
        assertEquals(initial.copy(revision = 11, folderStack = true), next)
        assertEquals(next, NativeRuleStateSnapshot.read { next.settingsValues() })
        assertNull(NativeRuleStateSnapshot.read { next.settingsValues() + (PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW to 1) })
        val ledger = NativeSnapshotLedger()
        ledger.offer(next)
        val restored = NativeSnapshotLedger().apply { restore(ledger.save()) }
        assertEquals(next, restored.latest())
        assertFalse(restored.offer(next.copy(folderStack = false)))
        val old = arrayOf<Any>(false, 4, false, 10L, 2L, false, true)
        val legacy = NativeSnapshotLedger().apply { restore(old) }
        assertEquals(initial.copy(recentsHaptic = true), legacy.latest())
        assertFalse(NativeRuleMutation.apply(next, PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, false, 12).folderStack)
    }
}
