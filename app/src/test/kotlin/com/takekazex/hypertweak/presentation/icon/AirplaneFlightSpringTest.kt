package com.takekazex.hypertweak.presentation.icon

import org.junit.Assert.*
import org.junit.Test

class AirplaneFlightSpringTest {
    @Test fun springStartsFromRestAcceleratesAndBrakesWithoutOvershoot() {
        val spring = AirplaneFlightSpring()
        spring.retarget(1f)
        spring.advance(0)
        assertEquals(0f, spring.position, 0f)
        assertEquals(0f, spring.velocity, 0f)
        spring.advance(15); val earlySpeed = spring.velocity
        spring.advance(55); val peakSpeed = spring.velocity
        spring.advance(350)
        assertTrue(peakSpeed > earlySpeed && spring.velocity < peakSpeed)
        var previous = spring.position
        for (time in 351L..900L) {
            spring.advance(time)
            assertTrue(spring.position in previous..1f)
            previous = spring.position
        }
        assertEquals(1f, spring.position, 0f)
        assertEquals(0f, spring.velocity, 0f)
    }

    @Test fun reversingKeepsVelocityAndBrieflyCoastsBeforeReturning() {
        val spring = AirplaneFlightSpring()
        spring.retarget(1f); spring.advance(90)
        val position = spring.position
        val velocity = spring.velocity
        spring.retarget(0f); spring.advance(0)
        assertEquals(position, spring.position, .000001f)
        assertEquals(velocity, spring.velocity, .000001f)
        spring.advance(1)
        assertTrue(spring.position > position)
        assertEquals(velocity, (spring.position - position) * 1000f, .2f)
        spring.advance(180)
        assertTrue(spring.position < position)
        assertTrue(spring.advance(900))
        assertEquals(0f, spring.position, 0f)
    }

    @Test fun springDoesNotDependOnFrameRateAndRepeatedInterruptionsStayBounded() {
        val dense = AirplaneFlightSpring().apply { retarget(1f) }
        val sparse = AirplaneFlightSpring().apply { retarget(1f) }
        for (time in 0L..127L) dense.advance(time)
        sparse.advance(127)
        assertEquals(dense.position, sparse.position, .000001f)
        assertEquals(dense.velocity, sparse.velocity, .000001f)
        repeat(60) { index ->
            dense.retarget(if (index % 2 == 0) 0f else 1f)
            for (time in 0L..80L step 8) {
                dense.advance(time)
                assertTrue(dense.position in 0f..1f && dense.velocity.isFinite())
            }
        }
        dense.snap(0f)
        assertEquals(0f, dense.velocity, 0f)
    }
}
