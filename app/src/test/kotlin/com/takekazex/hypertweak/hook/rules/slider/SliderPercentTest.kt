package com.takekazex.hypertweak.hook.rules.slider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliderPercentTest {
    @Test fun adjustableRangeEndpoints() {
        assertEquals(0, volumePercent(level = 2, min = 2, max = 12))
        assertEquals(100, volumePercent(level = 12, min = 2, max = 12))
    }

    @Test fun adjustableRangeMidpoint() {
        assertEquals(50, volumePercent(level = 7, min = 2, max = 12))
        assertEquals(27, volumePercent(level = 27, min = 0, max = 100))
    }

    @Test fun invalidRangesDoNotProduceAValue() {
        assertNull(volumePercent(level = 1, min = 1, max = 1))
        assertNull(volumePercent(level = 0, min = 1, max = 10))
        assertNull(volumePercent(level = 11, min = 1, max = 10))
    }
}
