package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.*
import org.junit.Test

class ChargingSampleGateTest {
    @Test fun invisibleOwnersNeverRequestAndBurstsHaveOneRead() {
        val gate = ChargingSampleGate()
        assertNull(gate.request(0, 1000))
        gate.start()
        val first = gate.request(0, 1000)!!
        repeat(50) { assertNull(gate.request(it.toLong(), 1000)) }
        assertTrue(gate.complete(first))
        assertNull(gate.request(999, 1000))
        assertNotNull(gate.request(1000, 1000))
    }

    @Test fun oldResultCannotWriteToReattachedOrReplacementOwner() {
        val gate = ChargingSampleGate()
        gate.start()
        val old = gate.request(0, 1000)!!
        gate.stop()
        gate.start()
        assertNull(gate.request(100, 1000))
        assertFalse(gate.complete(old))
        val new = gate.request(200, 1000)!!
        assertFalse(gate.complete(old))
        assertTrue(gate.complete(new))
        gate.stop()
        assertNull(gate.request(2000, 1000))
    }

    @Test fun stoppedReadReleasesSlotWithoutPublishingOrDelayingNextOwner() {
        val gate = ChargingSampleGate()
        gate.start()
        val first = gate.request(0, 1000)!!
        gate.stop()
        assertFalse(gate.complete(first))
        gate.start()
        assertNotNull(gate.request(11, 1000))
    }
}
