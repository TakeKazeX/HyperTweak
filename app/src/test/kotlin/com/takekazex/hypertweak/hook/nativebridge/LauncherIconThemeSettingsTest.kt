package com.takekazex.hypertweak.hook.nativebridge

import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import org.junit.Assert.*
import org.junit.Test

class LauncherIconThemeSettingsTest {
    @Test fun `old saved hosts migrate with icon theme disabled`() {
        val ledger = NativeSnapshotLedger()
        ledger.restore(arrayOf<Any>(false, 3, false, 7L, 2L, false, false, false, false, false, 100, 12))
        val snapshot = ledger.latest()!!
        assertEquals(0, snapshot.iconThemeMode)
        assertEquals(12, snapshot.desktopGrid)
        assertEquals(snapshot, NativeRuleStateSnapshot.read { snapshot.settingsValues() })
    }
    @Test fun `all modes survive mutation save restore and reset`() {
        for (mode in 0..4) {
            var snapshot = NativeRuleStateSnapshot(false, 3, false)
            snapshot = NativeRuleMutation.apply(snapshot, LauncherDesktopKeys.KEY_ICON_THEME_MODE, mode, 1)
            snapshot = NativeRuleMutation.apply(snapshot, LauncherDesktopKeys.KEY_ICON_THEME_COLOR, 0xff123456.toInt(), 2)
            val ledger = NativeSnapshotLedger(); assertTrue(ledger.offer(snapshot))
            val restored = NativeSnapshotLedger(); restored.restore(ledger.save())
            assertEquals(snapshot, restored.latest())
            assertEquals(0, NativeRuleStateSnapshot.read { emptyMap<String, Any>() }!!.iconThemeMode)
        }
    }
    @Test fun `retired refresh is ignored in old snapshots and preferences`() {
        val ledger = NativeSnapshotLedger()
        ledger.restore(arrayOf<Any>(false, 3, false, 7L, 2L, false, false, false, false, false, 100, 12, 3, 0xff123456.toInt(), true))
        assertEquals(3, ledger.latest()!!.iconThemeMode)
        assertEquals(14, ledger.save()!!.size)
        assertEquals(NativeRuleStateSnapshot.read { emptyMap<String, Any>() },
            NativeRuleStateSnapshot.read { mapOf("home_icon_theme_refresh" to true) })
    }
    @Test fun `malformed appearance cannot replace an acknowledged full snapshot`() {
        for (value in listOf(-1, 5, true, 1L, "1")) {
            assertNull(NativeRuleStateSnapshot.read { mapOf(LauncherDesktopKeys.KEY_ICON_THEME_MODE to value) })
        }
        assertNull(NativeRuleStateSnapshot.read { mapOf(LauncherDesktopKeys.KEY_ICON_THEME_COLOR to "#123456") })
        val registry = com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
        for (key in listOf(LauncherDesktopKeys.KEY_ICON_THEME_MODE, LauncherDesktopKeys.KEY_ICON_THEME_COLOR)) {
            assertTrue(registry.owners(key).isNotEmpty())
        }
    }
}
