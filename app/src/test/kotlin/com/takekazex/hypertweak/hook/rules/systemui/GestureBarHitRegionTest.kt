package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureBarHitRegionTest {
    private fun contains(
        x: Float,
        y: Float,
        handleCenterX: Float? = 540f,
        handleWidth: Float? = 456f
    ): Boolean = GestureBarHitRegion.contains(
        x = x,
        y = y,
        screenWidth = 1080,
        screenHeight = 2400,
        density = 3f,
        handleCenterX = handleCenterX,
        handleWidth = handleWidth
    )

    @Test
    fun centerOfBottomStripIsInside() {
        assertTrue(contains(x = 540f, y = 2390f))
    }

    @Test
    fun centerOfScreenIsOutside() {
        assertFalse(contains(x = 540f, y = 1200f))
    }

    @Test
    fun bottomCornerIsOutside() {
        assertFalse(contains(x = 30f, y = 2390f))
    }

    @Test
    fun fullScreenHandleViewDoesNotExpandHitRegion() {
        assertFalse(contains(x = 30f, y = 2390f, handleCenterX = 540f, handleWidth = 1080f))
        assertTrue(contains(x = 540f, y = 2390f, handleCenterX = 540f, handleWidth = 1080f))
    }

    @Test
    fun missingHandleUsesCenteredFallback() {
        assertTrue(contains(x = 540f, y = 2390f, handleCenterX = null, handleWidth = null))
        assertFalse(contains(x = 100f, y = 2390f, handleCenterX = null, handleWidth = null))
    }
}
