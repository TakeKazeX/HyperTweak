package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test

class NativeSnapshotLedgerTest {
    @Test fun `late daemon callback cannot undo a committed app snapshot`() {
        val ledger = NativeSnapshotLedger()
        val old = NativeRuleStateSnapshot(true, 4, false, 10, 3)
        val new = old.copy(columns = 5, revision = 11)
        assertTrue(ledger.offer(old)); assertTrue(ledger.offer(new))
        assertFalse(ledger.offer(old)); assertEquals(new, ledger.latest())
    }
    @Test fun `reset epoch can replace a larger previous commit identity`() {
        val ledger = NativeSnapshotLedger()
        assertTrue(ledger.offer(NativeRuleStateSnapshot(true, 5, true, 800, 3)))
        val reset = NativeRuleStateSnapshot(false, 3, false, 0, 4)
        assertTrue(ledger.offer(reset))
        assertFalse(ledger.offer(reset.copy(epoch = 3, revision = 900)))
        assertEquals(reset, ledger.latest())
    }
    @Test fun `same commit conflict is rejected but replay is allowed`() {
        val ledger = NativeSnapshotLedger()
        val current = NativeRuleStateSnapshot(false, 4, true, 1, 1)
        assertTrue(ledger.offer(current)); assertTrue(ledger.offer(current))
        assertFalse(ledger.offer(current.copy(hidden = true)))
    }
    @Test fun `commit order survives a lower clock and cannot overflow`() {
        assertEquals(901, NativeSnapshotLedger.nextRevision(900, 1))
        assertEquals(1000, NativeSnapshotLedger.nextRevision(900, 1000))
        assertThrows(IllegalArgumentException::class.java) { NativeSnapshotLedger.nextRevision(Long.MAX_VALUE, 1) }
    }
    @Test fun `partial malformed metadata and invalid columns never become defaults`() {
        for (value in listOf<Any>(-1L, "1", 1)) assertNull(NativeRuleStateSnapshot.read {
            mapOf(Preferences.KEY_NATIVE_RULE_REVISION to value)
        })
        assertNull(NativeRuleStateSnapshot.read { mapOf(Preferences.KEY_OPENED_FOLDER_COLUMNS to 6) })
    }
    @Test fun `replacement generation retains authority using framework transferable values`() {
        val previous = NativeSnapshotLedger()
        val committed = NativeRuleStateSnapshot(true, 5, true, 100, 3)
        previous.offer(committed)
        val replacement = NativeSnapshotLedger()
        replacement.restore(previous.save())
        assertFalse(replacement.offer(committed.copy(columns = 4, revision = 99)))
        assertEquals(committed, replacement.latest())
        replacement.restore(arrayOf<Any>(false, 3, false, "wrong", 5L))
        assertEquals(committed, replacement.latest())
    }
}
