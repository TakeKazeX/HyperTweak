package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class GlyphMotionLeaseTest {
    @Test fun duoPreemptionRestoresOpacityBeforeCapturingItAndRejectsTheFallback() {
        val lease = GlyphMotionLease { throw AssertionError(it) }
        val native = Any(); val duo = Any()
        var alpha = 0f
        assertTrue(lease.acquire(native, GlyphMotionLease.LEFT_OR_NATIVE) { alpha = 1f; lease.release(native) })
        assertTrue(lease.acquire(duo, GlyphMotionLease.DUO) {})
        assertEquals(1f, alpha, 0f)
        assertFalse(lease.acquire(native, GlyphMotionLease.LEFT_OR_NATIVE) { error("must not acquire") })
        lease.release(native) // A retired old writer cannot release the current owner.
        assertFalse(lease.acquire(Any(), GlyphMotionLease.DUO) {})
        lease.release(duo)
        assertTrue(lease.acquire(native, GlyphMotionLease.LEFT_OR_NATIVE) {})
    }

    @Test fun retirementRestoresOnlyOnceAndSameOwnerDoesNotRecapture() {
        val lease = GlyphMotionLease { throw AssertionError(it) }
        val owner = Any(); var restores = 0
        assertTrue(lease.acquire(owner, 10) { restores++ })
        assertTrue(lease.acquire(owner, 10) { restores += 10 })
        lease.retire(); lease.retire()
        assertEquals(1, restores)
    }

    @Test fun failedOldCleanupDoesNotLeaveTheNewWriterBlocked() {
        val failures = mutableListOf<Throwable>()
        val lease = GlyphMotionLease { failures += it }
        assertTrue(lease.acquire(Any(), 10) { error("old restore") })
        val next = Any()
        assertTrue(lease.acquire(next, 20) {})
        assertEquals("old restore", failures.single().message)
        assertFalse(lease.acquire(Any(), 10) {})
        lease.release(next)
        assertTrue(lease.acquire(Any(), 10) {})
    }
}
