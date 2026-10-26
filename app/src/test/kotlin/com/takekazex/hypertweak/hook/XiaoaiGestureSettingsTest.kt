package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.nativebridge.NativeRuleMutation
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.nativebridge.NativeSnapshotLedger
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import org.junit.Assert.*
import org.junit.Test

class XiaoaiGestureSettingsTest {
    @Test fun acknowledgedSnapshotRoundtripAndLegacyRecovery() {
        val old = NativeRuleStateSnapshot(false, 3, false, revision = 10)
        val enabled = NativeRuleMutation.apply(old, PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, true, 11)
        assertTrue(enabled.xiaoaiOptions)
        assertEquals(enabled, NativeRuleStateSnapshot.read { enabled.settingsValues() })
        assertNull(NativeRuleStateSnapshot.read { enabled.settingsValues() + (PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS to 1) })
        val ledger = NativeSnapshotLedger()
        assertTrue(ledger.offer(enabled))
        assertFalse(ledger.offer(old))
        val restored = NativeSnapshotLedger().apply { restore(ledger.save()) }
        assertEquals(enabled, restored.latest())
        for (size in 6..9) {
            val legacy = NativeSnapshotLedger().apply { restore(ledger.save()!!.copyOf(size)) }
            assertFalse(legacy.latest()!!.xiaoaiOptions)
        }
        assertFalse(NativeRuleMutation.apply(enabled, PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, false, 12).xiaoaiOptions)
        assertTrue(RuntimeSettingsGroups.forProcess("com.miui.voiceassist").contains(RuntimeSettingsGroups.XIAOAI_GESTURES))
    }
}
