package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeKeyStepPolicyTest {
    @Test fun lowResolutionRangeDoesNotFallBackToNativeSteps() {
        for (steps in 5..100) {
            val sequence = VolumeKeyStepSequence()
            val configuration = VolumeKeyStepPolicy.configuration(steps, 0)
            assertEquals(15, (1..steps).sumOf { sequence.next(15, configuration, 1) })
        }
    }

    @Test fun liveCountScopeAndRangeChangesDiscardOldRemainders() {
        val sequence = VolumeKeyStepSequence()
        assertEquals(0, sequence.next(15, VolumeKeyStepPolicy.configuration(20, 0), 1))
        assertEquals(0, sequence.next(15, VolumeKeyStepPolicy.configuration(25, 0), 1))
        assertEquals(0, sequence.next(15, VolumeKeyStepPolicy.configuration(25, 1), 1))
        assertEquals(0, sequence.next(10, VolumeKeyStepPolicy.configuration(25, 1), 1))
    }

    @Test fun oppositeDirectionsHaveIndependentRemainders() {
        val sequence = VolumeKeyStepSequence()
        val config = VolumeKeyStepPolicy.configuration(20, 0)
        assertEquals(0, sequence.next(15, config, 1))
        assertEquals(0, sequence.next(15, config, -1))
        assertEquals(1, sequence.next(15, config, 1))
        assertEquals(1, sequence.next(15, config, -1))
    }
}
