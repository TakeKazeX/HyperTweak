package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockRecoveryPolicyTest {
    @Test fun repeatedWmsTraversalsCannotAllocateOnEveryFailure() {
        val policy = DockRecoveryPolicy()
        assertTrue(policy.permit("A", 0))
        assertEquals(2000L, policy.failed("A", 0))
        repeat(2000) { assertFalse(policy.permit("A", it.toLong())) }
        assertTrue(policy.permit("A", 2000))
    }
    @Test fun retriesEndUntilAnExternalBoundaryOrConfigurationChange() {
        val policy = DockRecoveryPolicy()
        var now = 0L
        repeat(3) {
            assertTrue(policy.permit("A", now))
            now += requireNotNull(policy.failed("A", now))
        }
        assertTrue(policy.permit("A", now))
        assertNull(policy.failed("A", now))
        assertFalse(policy.permit("A", Long.MAX_VALUE))
        assertTrue(policy.permit("B", now))
        policy.failed("B", now)
        policy.reset()
        assertTrue(policy.permit("B", now))
    }
}
