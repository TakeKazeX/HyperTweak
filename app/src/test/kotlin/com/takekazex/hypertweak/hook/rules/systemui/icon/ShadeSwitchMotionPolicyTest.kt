package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeSwitchMotionPolicyTest {
    @Test fun switchingFlagCanEndBeforeProgressReturnsToAnEndpoint() {
        assertTrue(ShadeSwitchMotionPolicy.isActive(true, 1f))
        assertTrue(ShadeSwitchMotionPolicy.isActive(false, 0.77910554f))
        assertFalse(ShadeSwitchMotionPolicy.isActive(false, 0f))
        assertFalse(ShadeSwitchMotionPolicy.isActive(false, 1f))
    }

    @Test fun settledEndpointSelectsTheVisibleShade() {
        assertTrue(ShadeSwitchMotionPolicy.controlCenterAtRest(0f))
        assertFalse(ShadeSwitchMotionPolicy.controlCenterAtRest(1f))
    }
}
