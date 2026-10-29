package com.takekazex.hypertweak.settings.contract

import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.nativebridge.NativeSnapshotLedger
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleMutation
import org.junit.Assert.*
import org.junit.Test

class DesktopGridSettingsTest {
    @Test fun twelveLayoutsRoundTripWithoutConflatingFollowHost() {
        val codes = (4..6).flatMap { x -> (5..8).map { y ->
            LauncherDesktopKeys.gridCode(x, y).also {
                assertEquals(x, LauncherDesktopKeys.gridColumns(it))
                assertEquals(y, LauncherDesktopKeys.gridRows(it))
            }
        } }
        assertEquals((1..12).toSet(), codes.toSet())
        assertFalse(LauncherDesktopKeys.validGrid(-1))
        assertFalse(LauncherDesktopKeys.validGrid(13))
        assertTrue(LauncherDesktopKeys.validGrid(0))
    }
    @Test fun incompatibleSettingsRejectTheWholeSnapshot() {
        for (value in listOf<Any>(13, -1, "12", 12L)) {
            assertNull(NativeRuleStateSnapshot.read { mapOf(LauncherDesktopKeys.KEY_GRID to value) })
        }
        assertEquals(0, NativeRuleStateSnapshot.read { emptyMap<String, Any>() }!!.desktopGrid)
    }
    @Test fun oldLedgerFollowsHostAndNewLedgerRetainsGridAcrossReload() {
        val ledger = NativeSnapshotLedger()
        ledger.restore(arrayOf<Any>(false, 3, false, 1L, 1L, false, false, false, false, false, -1))
        assertEquals(0, ledger.latest()!!.desktopGrid)
        val next = NativeRuleMutation.apply(ledger.latest()!!, LauncherDesktopKeys.KEY_GRID, 12, 2)
        assertEquals(12, next.settingsValues()[LauncherDesktopKeys.KEY_GRID])
        assertTrue(ledger.offer(next))
        val restored = NativeSnapshotLedger()
        restored.restore(ledger.save())
        assertEquals(next, restored.latest())
    }
    @Test fun configuredDoesNotMeanApplied() {
        assertFalse(DesktopGridStatus(1, 12, 2).applied)
        assertFalse(DesktopGridStatus(5, 12, 12).applied)
        assertFalse(DesktopGridStatus(6, 12, 11).applied)
        assertTrue(DesktopGridStatus(6, 12, 12).applied)
        assertFalse(DesktopGridStatus(7, 12, 2).restored)
    }
}
