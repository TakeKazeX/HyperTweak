package com.takekazex.hypertweak.ui.effect

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleBackFrameTest {
    @Test fun cancellationReturnsToFullSizeWithoutExitTranslation() {
        assertEquals(0.925f, scaleBackFrame(0.5f, 0.5f, false).scale, 0.0001f)
        val restored = scaleBackFrame(0f, 0.5f, false)
        assertEquals(1f, restored.scale, 0f)
        assertEquals(0f, restored.translationFraction, 0f)
    }

    @Test fun completionContinuesFromReleaseWithoutJumping() {
        val release = scaleBackFrame(0.4f, 0.4f, true)
        assertEquals(scaleBackFrame(0.4f, 0.4f, false).scale, release.scale, 0f)
        assertEquals(0f, release.translationFraction, 0.0001f)
        assertEquals(0.5f, scaleBackFrame(0.7f, 0.4f, true).translationFraction, 0.0001f)
        assertEquals(1f, scaleBackFrame(1f, 0.4f, true).translationFraction, 0f)
    }

    @Test fun completionAtMaximumGestureProgressRemainsFinite() {
        val frame = scaleBackFrame(1f, 1f, true)
        assertEquals(1f, frame.translationFraction, 0f)
        assertEquals(true, frame.scale.isFinite())
    }
}
