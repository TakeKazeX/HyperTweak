package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import org.junit.Assert.*
import org.junit.Test
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups

class AndroidWidgetStackSettingsTest {
    @Test fun authoritativeMutationResetAndUpgrade() {
        val old = NativeRuleStateSnapshot(true, 5, true, 10, 2, true, true, true)
        val enabled = NativeRuleMutation.apply(old, PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, true, 11)
        assertEquals(old.copy(widgetStack = true, revision = 11), enabled)
        assertEquals(enabled, NativeRuleStateSnapshot.read { enabled.settingsValues() })
        assertNull(NativeRuleStateSnapshot.read { enabled.settingsValues() + (PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK to 1) })
        assertEquals(setOf(Groups.NATIVE), Groups.changed(old.settingsValues(), enabled.settingsValues()))
        assertEquals(setOf(Groups.NATIVE), Groups.changed(enabled.settingsValues(), old.copy(epoch = 3).settingsValues()))
        val ledger = NativeSnapshotLedger().apply { offer(enabled) }
        val restored = NativeSnapshotLedger().apply { restore(ledger.save()) }
        assertEquals(enabled, restored.latest())
        assertFalse(restored.offer(enabled.copy(widgetStack = false)))
        // Old mapped Java state has eight fields. Keep all existing options;
        // only the new widget-stack setting defaults to false.
        val legacy = NativeSnapshotLedger().apply { restore(ledger.save()!!.take(8).toTypedArray()) }
        assertEquals(enabled.copy(widgetStack = false), legacy.latest())
        assertFalse(NativeRuleMutation.apply(enabled, PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, false, 12).widgetStack)
    }
}
