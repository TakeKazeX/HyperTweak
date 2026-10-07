package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockLeaseLedgerTest {
    @Test fun delayedOldCreateCannotRepopulateAfterRetirementOrReplacement() {
        val leases = DockLeaseLedger()
        assertTrue(leases.admit(100))
        leases.retire(100)
        assertFalse(leases.admit(100))
        assertTrue(leases.admit(200))
        assertFalse(leases.admit(101))
        assertFalse(leases.admit(100))
    }
    @Test fun oldCleanupCannotRetireNewRendererAndNewGenerationCanRecover() {
        val leases = DockLeaseLedger()
        assertTrue(leases.admit(200))
        leases.retire(100)
        assertTrue(leases.admit(200))
        leases.retire(200)
        assertFalse(leases.admit(200))
        assertTrue(leases.admit(300))
    }
}
