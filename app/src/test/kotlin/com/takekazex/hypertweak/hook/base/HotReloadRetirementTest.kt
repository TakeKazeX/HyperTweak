package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class HotReloadRetirementTest {
    @Test fun failedSnapshotRejectsBeforeAnyOwnerIsRetired() {
        var live = true
        var rejected = false
        assertFalse(HotReloadRetirement.run(
            preflight = { error("saved-state publication rejected") },
            rejected = { rejected = true }, cleanupFailed = { live = false }
        ))
        assertTrue(live)
        assertTrue(rejected)
    }

    @Test fun partialTeardownStillRetiresAllOwnersAndAcceptsReplacement() {
        val events = mutableListOf<String>()
        val first = IllegalStateException("host callback failed")
        val second = IllegalStateException("receiver failed")
        var reported: Throwable? = null
        assertTrue(HotReloadRetirement.run(preflight = {
            listOf(
                { events += "retire:A"; throw first },
                { events += "retire:B" },
                { events += "receiver"; throw second },
                { events += "publisher" }
            )
        }, rejected = { fail("Cannot resume retired owners") }, cleanupFailed = { reported = it }))
        assertEquals(listOf("retire:A", "retire:B", "receiver", "publisher"), events)
        assertSame(first, reported)
        assertArrayEquals(arrayOf(second), first.suppressed)
    }

    @Test fun failedDiagnosticsCannotRejectAnAlreadyRetiredGeneration() {
        assertTrue(HotReloadRetirement.run(
            preflight = { listOf({ error("cleanup") }) },
            rejected = { fail() }, cleanupFailed = { error("logger") }
        ))
    }
}
