package com.takekazex.hypertweak.hook.nativebridge

import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import org.junit.Assert.*
import org.junit.Test

class DesktopIconSettingsTest {
    @Test fun resetAndOldBackupsFollowHostWhileInvalidValuesRejectTheWholeSnapshot() {
        assertEquals(-1, NativeRuleStateSnapshot.read { emptyMap<String, Any>() }!!.iconPercent)
        for (value in listOf<Any>(-2, 0, 49, 151, 100L, "100", true)) {
            assertNull(NativeRuleStateSnapshot.read { mapOf(LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT to value) })
        }
        for (value in listOf(-1, 50, 100, 150)) {
            val snapshot = NativeRuleStateSnapshot.read { mapOf(LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT to value) }!!
            assertEquals(value, snapshot.settingsValues()[LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT])
        }
    }

    @Test fun sizeSurvivesIndependentNativeEditsAndClassloaderReplacement() {
        val baseline = NativeRuleStateSnapshot(false, 4, false, 5, 2)
        val edited = NativeRuleMutation.apply(baseline, LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT, 140, 6)
        val old = NativeSnapshotLedger()
        assertTrue(old.offer(edited))
        assertFalse(old.offer(edited.copy(iconPercent = 90)))
        val replacement = NativeSnapshotLedger()
        replacement.restore(old.save())
        assertEquals(140, replacement.latest()!!.iconPercent)
        val oldGeneration = old.save()!!.copyOfRange(0, 10)
        val legacy = NativeSnapshotLedger()
        legacy.restore(oldGeneration)
        assertEquals(-1, legacy.latest()!!.iconPercent)
        assertThrows(IllegalArgumentException::class.java) {
            NativeRuleMutation.apply(baseline, LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT, 151, 6)
        }
    }
}
