package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cellular-type fade curve.
 *
 * The frames drive a bitmap alpha for both type renderers, and the last frame has to land exactly
 * on the target: a suppressed glyph that stops at 0.02 stays faintly visible, and a fade that
 * overshoots would make the glyph darker than the row it sits in.
 */
class BitmapAlphaFadeTest {

    @Test
    fun fadeOutStartsBelowOneAndEndsExactlyAtZero() {
        val steps = fadeAlphas(current = 1f, target = 0f, frames = 8)

        assertEquals(8, steps.size)
        assertTrue(steps.first() < 1f)
        assertEquals(0f, steps.last(), 0f)
        assertTrue(steps.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun fadeInIsMonotonicAndEndsAtOne() {
        val steps = fadeAlphas(current = 0f, target = 1f, frames = 4)

        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f), steps)
    }

    @Test
    fun anAlreadySettledAlphaProducesOneFrame() {
        assertEquals(listOf(0f), fadeAlphas(current = 0f, target = 0f, frames = 8))
        assertEquals(listOf(1f), fadeAlphas(current = 1f, target = 1f, frames = 8))
    }

    @Test
    fun degenerateFrameCountsStillReachTheTarget() {
        assertEquals(listOf(0f), fadeAlphas(current = 1f, target = 0f, frames = 0))
        assertEquals(listOf(1f), fadeAlphas(current = 0f, target = 1f, frames = -3))
    }

    @Test
    fun outOfRangeInputsAreClamped() {
        assertEquals(listOf(1f), fadeAlphas(current = 4f, target = 9f, frames = 4))
        assertEquals(listOf(0f), fadeAlphas(current = -2f, target = -1f, frames = 4))
    }
}
