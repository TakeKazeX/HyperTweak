package com.takekazex.hypertweak.util.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DebugAccessSessionTest {
    @Test fun grantsExpireOnDeadlineBootOrPackageReplacement() {
        assertEquals(10L, DebugAccessSession.remainingFor(110, 100, 3, 3, 50, 50))
        assertEquals(0L, DebugAccessSession.remainingFor(100, 100, 3, 3, 50, 50))
        assertEquals(0L, DebugAccessSession.remainingFor(110, 100, 3, 4, 50, 50))
        assertEquals(0L, DebugAccessSession.remainingFor(110, 100, 3, 3, 50, 51))
        assertEquals(0L, DebugAccessSession.remainingFor(110, 100, -1, -1, 50, 50))
    }
    @Test fun onlyLiveRootSessionMayReadOrWrite() {
        DebugAccessSession.requireRoot(0, 1)
        for ((uid, remaining) in listOf(2000 to 100L, 1000 to 100L, 10123 to 100L, 0 to 0L, 0 to -1L)) {
            try { DebugAccessSession.requireRoot(uid, remaining); fail("Unexpected access for $uid") }
            catch (_: IllegalStateException) { }
        }
    }
}
