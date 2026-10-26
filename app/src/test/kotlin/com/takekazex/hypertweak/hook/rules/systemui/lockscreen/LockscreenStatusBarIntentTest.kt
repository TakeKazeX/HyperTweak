package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import com.takekazex.hypertweak.hook.rules.systemui.controlcenter.CornerOverridePolicy
import com.takekazex.hypertweak.hook.rules.systemui.controlcenter.CornerOverrideRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review's behaviour breakpoints for the lockscreen status bar: the host's own request must
 * survive a module suppression, and a value the module never suppressed must not be rewritten
 * when the feature is turned off.
 */
class LockscreenStatusBarIntentTest {
    @Test fun nativeVisibilityRequestUpdatesOnlyTheVisibilityDimension() {
        val intent = LockscreenStatusBarIntent(0, 1f).withVisibility(8)
        assertEquals(8, intent.visibility)
        assertEquals(1f, intent.alpha, 0f)
    }

    @Test fun nativeAlphaRequestUpdatesOnlyTheAlphaDimension() {
        val intent = LockscreenStatusBarIntent(8, 1f).withAlpha(0.35f)
        assertEquals(8, intent.visibility)
        assertEquals(0.35f, intent.alpha, 0f)
    }

    @Test fun aNativeHideFollowedByARevealIsWhatDisableRestores() {
        // The host asked for GONE, then VISIBLE while the row was hidden by the module. A restore
        // writes the last request, not the module's own INVISIBLE/0.
        var intent = LockscreenStatusBarIntent(0, 1f)
        intent = intent.withVisibility(8)
        intent = intent.withVisibility(0)
        assertEquals(0, intent.visibility)
        assertEquals(1f, intent.alpha, 0f)
    }

    @Test fun reEnablingStartsFromTheHostValuesNotTheSuppression() {
        var intent = LockscreenStatusBarIntent(0, 1f).withVisibility(4).withAlpha(0f)
        assertEquals(4, intent.visibility)
        assertEquals(0f, intent.alpha, 0f)
        intent = intent.withVisibility(0)
        assertEquals(0, intent.visibility)
    }

    @Test fun missingOrDisabledValuesAreNoOverride() {
        assertFalse(CornerOverridePolicy.applies(enabled = false, radiusPx = 48f))
        assertFalse(CornerOverridePolicy.applies(enabled = true, radiusPx = 0f))
        assertFalse(CornerOverridePolicy.applies(enabled = true, radiusPx = -1f))
        assertFalse(CornerOverridePolicy.applies(enabled = true, radiusPx = Float.NaN))
        assertTrue(CornerOverridePolicy.applies(enabled = true, radiusPx = 48f))
    }

    @Test fun anOverrideIsReleasedToTheHostRadius() {
        val record = CornerOverrideRecord(hostRadius = 24f, background = null)
        assertEquals(24f, CornerOverridePolicy.releaseRadius(record) ?: 0f, 0f)
        assertNull(CornerOverridePolicy.releaseRadius(null))
    }
}
