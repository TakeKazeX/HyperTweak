package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeformBlurMathTest {

    @Test
    fun blurRadius_positiveFolmeValueWins() {
        // The ROM's own 100 -> 0 interpolation is authoritative while it is running.
        assertEquals(42f, freeformBlurRadius(42f, 1f), 0f)
        assertEquals(0.5f, freeformBlurRadius(0.5f, 0.9f), 0f)
    }

    @Test
    fun blurRadius_zeroWithVisibleIconIsDerivedFromIconAlpha() {
        assertEquals(100f, freeformBlurRadius(0f, 1f), 0f)
        assertEquals(50f, freeformBlurRadius(0f, 0.5f), 0f)
    }

    @Test
    fun blurRadius_zeroIconAlphaStaysZero() {
        // Nothing is visible, so there is no mask to keep alive.
        assertEquals(0f, freeformBlurRadius(0f, 0f), 0f)
    }

    @Test
    fun blurRadius_negativeIsTreatedAsAbsent() {
        assertEquals(100f, freeformBlurRadius(-1f, 1f), 0f)
    }

    @Test
    fun blurRadius_rampsDownMonotonicallyWithIconAlpha() {
        val samples = listOf(1f, 0.75f, 0.5f, 0.25f, 0f).map { freeformBlurRadius(0f, it) }
        assertEquals(samples.sortedDescending(), samples)
    }

    @Test
    fun darkAlpha_positiveFolmeValueWins() {
        assertEquals(0.3f, freeformDarkAlpha(0.3f, 0f, 1f), 0f)
    }

    @Test
    fun darkAlpha_zeroIsDerivedFromIconAlpha() {
        assertEquals(0.2f, freeformDarkAlpha(0f, 0f, 1f), 0.001f)
        assertEquals(0.1f, freeformDarkAlpha(0f, 0f, 0.5f), 0.001f)
    }

    @Test
    fun darkAlpha_zeroIconAlphaStaysZero() {
        assertEquals(0f, freeformDarkAlpha(0f, 0f, 0f), 0f)
    }

    @Test
    fun darkAlpha_neverDarkensWhenAnExplicitScrimExists() {
        // An explicit scrim from Folme must survive untouched even with a visible icon.
        assertEquals(0.6f, freeformDarkAlpha(0.6f, 0f, 1f), 0f)
    }

    @Test
    fun darkAlpha_leftAloneWhenTheFrameCarriesRealBlur() {
        // The ROM deliberately passed blur without a scrim; do not add one.
        assertEquals(0f, freeformDarkAlpha(0f, 42f, 1f), 0f)
    }
}
