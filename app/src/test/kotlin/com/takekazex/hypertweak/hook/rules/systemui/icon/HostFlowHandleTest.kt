package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class HostFlowHandleTest {
    class Job(var active: Boolean) {
        var cancellations = 0
        fun isActive() = active
        fun cancel() { cancellations++; active = false }
    }

    @Test fun inactiveScopeIsRejectedAndCancellationIsIdempotent() {
        val job = Job(false)
        val handle = HostFlowCollector.Handle(job)
        assertFalse(handle.isActive())
        handle.cancel()
        handle.cancel()
        assertEquals(1, job.cancellations)
    }

    @Test fun liveSubscriptionRemainsUsableUntilRetirement() {
        val job = Job(true)
        val handle = HostFlowCollector.Handle(job)
        assertTrue(handle.isActive())
        handle.cancel()
        assertFalse(handle.isActive())
    }
}
