package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.settings.contract.VolumeKeyStepPolicy
import org.junit.Assert.*
import org.junit.Test

class VolumeKeyStepPolicyTest {
    private fun next(current: Int, direction: Int, steps: Int = 20, min: Int = 0, max: Int = 150) =
        VolumeKeyStepPolicy.nextIndex(current, min, max, steps, direction)
    private fun percent(index: Int) = kotlin.math.round(index * 100f / 150).toInt()

    @Test fun twentyStepsAddFivePercentFromDraggedSeventeenPercentWithoutSnapping() {
        // Public index 26 reads as 17%; it must go to 12/22%, never a multiple-of-five grid.
        assertEquals(12, percent(next(26, -1)))
        assertEquals(22, percent(next(26, 1)))
        assertEquals(18, next(26, -1))
        assertEquals(33, next(26, 1))
    }

    @Test fun repeatedReversalsKeepTheCurrentPercentageOffset() {
        var current = 26
        repeat(200) {
            current = next(current, 1)
            assertEquals(22, percent(current))
            current = next(current, -1)
            assertEquals(17, percent(current))
        }
    }

    @Test fun everyInteriorPercentageGetsTheSameIncrementAndDecrement() {
        for (percent in 5..95) {
            val current = (150 * percent + 50) / 100
            assertEquals(percent + 5, percent(next(current, 1)))
            assertEquals(percent - 5, percent(next(current, -1)))
        }
    }

    @Test fun coarseStreamsMakeProgressAndEndpointsClamp() {
        for (range in listOf(7, 14, 15, 100, 150)) for (steps in 5..100) {
            for (current in 1..range + 1) {
                val up = next(current, 1, steps, 1, range + 1)
                val down = next(current, -1, steps, 1, range + 1)
                assertTrue(up in current..range + 1)
                assertTrue(down in 1..current)
                if (current < range + 1) assertTrue(up > current)
                if (current > 1) assertTrue(down < current)
            }
        }
        assertEquals(0, next(0, -1))
        assertEquals(150, next(150, 1))
        assertEquals(12, next(12, 1, min = 12, max = 12))
    }

    @Test fun liveConfigurationReloadAndOldInstallationsNeedNoRemainderState() {
        assertEquals(21, percent(next(26, 1, steps = 25)))
        assertEquals(7, VolumeKeyStepPolicy.percentStep(15))
        assertTrue(VolumeKeyStepPolicy.configuration(20, 0).enabled)
        assertFalse(VolumeKeyStepPolicy.configuration(20, 0, false).enabled)
        assertFalse(VolumeKeyStepPolicy.appliesToStream(0, 2))
        assertTrue(VolumeKeyStepPolicy.appliesToStream(0, 3))
        assertTrue(VolumeKeyStepPolicy.appliesToStream(1, 2))
    }
}
