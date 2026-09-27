package com.takekazex.hypertweak.hook.rules.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMasterLiveCompatibilityTest {
    private val stops = floatArrayOf(0.7f, 1f, 2f, 5f, 10f)

    @Test fun `matching DDF and native photo and donor live focal contracts permit effects`() {
        assertTrue(CameraMasterLiveCompatibility.accepts(42, 42, stops, stops.copyOf(), stops.copyOf()))
    }

    @Test fun `missing or mismatched DDF rejects borrowing`() {
        for (id in listOf(null, 0, -1, 43)) {
            assertFalse(CameraMasterLiveCompatibility.accepts(42, id, stops, stops, stops))
        }
        assertFalse(CameraMasterLiveCompatibility.accepts(null, null, stops, stops, stops))
        assertFalse(CameraMasterLiveCompatibility.accepts(0, 0, stops, stops, stops))
    }

    @Test fun `missing invalid unordered or differing focal contracts reject borrowing`() {
        val invalid = listOf(null, floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY),
            floatArrayOf(0f, 1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f),
            stops.reversedArray(), floatArrayOf(0.7f, 1f, 2f, 5f))
        for (values in invalid) {
            assertFalse(CameraMasterLiveCompatibility.accepts(42, 42, values, stops, stops))
            assertFalse(CameraMasterLiveCompatibility.accepts(42, 42, stops, values, stops))
            assertFalse(CameraMasterLiveCompatibility.accepts(42, 42, stops, stops, values))
        }
    }
}
