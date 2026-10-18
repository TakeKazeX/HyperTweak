package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandModePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandModePolicyTest {
    @Test fun `trimming is independent from three task scheduling and can be disabled`() {
        assertTrue(IslandModePolicy.enabled(false, false, 0, false, false, false, true))
        val trimOnly = IslandModePolicy.flags(true, IslandOrientation.PORTRAIT, false, false, false, true)
        assertTrue(trimOnly.portraitCompact)
        assertFalse(trimOnly.summarySwipe)
        val threeOnly = IslandModePolicy.flags(true, IslandOrientation.PORTRAIT, true, false, false, false)
        assertFalse(threeOnly.portraitCompact)
        assertTrue(threeOnly.summarySwipe)
    }

    @Test fun `session enablement requires a switch and a healthy lifecycle`() {
        assertTrue(IslandModePolicy.enabled(false, false, 0, true, false, false))
        assertTrue(IslandModePolicy.enabled(false, false, 0, false, true, false))
        assertTrue(IslandModePolicy.enabled(false, false, 0, false, false, true))
        assertFalse(IslandModePolicy.enabled(false, false, 0, false, false, false))
        assertFalse(IslandModePolicy.enabled(true, false, 0, true, true, true))
        assertFalse(IslandModePolicy.enabled(false, true, 0, true, true, true))
        assertFalse(IslandModePolicy.enabled(false, false, 1, true, true, true))
    }

    @Test fun `phone eligibility excludes undefined tiny rebuilding and transient owners`() {
        fun eligible(
            orientation: IslandOrientation = IslandOrientation.PORTRAIT,
            enabled: Boolean = true,
            widthDp: Int = 411,
            tiny: Boolean = false,
            rebuilding: Boolean = false,
            showOnce: Boolean = false,
            tempShow: Boolean = false,
        ) = IslandModePolicy.phoneEligible(
            enabled, orientation, widthDp, tiny, rebuilding, showOnce, tempShow,
        )

        assertTrue(eligible())
        assertTrue(eligible(orientation = IslandOrientation.LANDSCAPE))
        assertFalse(eligible(orientation = IslandOrientation.UNDEFINED))
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(widthDp = 600))
        assertTrue(eligible(widthDp = 599))
        assertFalse(eligible(tiny = true))
        assertFalse(eligible(rebuilding = true))
        assertFalse(eligible(showOnce = true))
        assertFalse(eligible(tempShow = true))
    }

    @Test fun `landscape switches only enable row mode on an eligible landscape window`() {
        val full = IslandModePolicy.flags(true, IslandOrientation.LANDSCAPE, true, true, false)
        assertTrue(full.landscapeFull)
        assertFalse(full.landscapeMore)
        assertTrue(full.rowMode)
        assertFalse(full.portraitCompact)
        assertTrue(full.summarySwipe)

        val more = IslandModePolicy.flags(true, IslandOrientation.LANDSCAPE, true, false, true)
        assertFalse(more.landscapeFull)
        assertTrue(more.landscapeMore)
        assertTrue(more.rowMode)
        assertFalse(more.portraitCompact)
        assertTrue(more.summarySwipe)

        val portrait = IslandModePolicy.flags(true, IslandOrientation.PORTRAIT, true, true, true)
        assertFalse(portrait.landscapeFull)
        assertFalse(portrait.landscapeMore)
        assertFalse(portrait.rowMode)
        assertTrue(portrait.portraitCompact)
        assertTrue(portrait.summarySwipe)

        val ineligible = IslandModePolicy.flags(false, IslandOrientation.LANDSCAPE, true, true, true)
        assertFalse(ineligible.rowMode)
        assertFalse(ineligible.portraitCompact)
        assertFalse(ineligible.summarySwipe)

        val swipeOnly = IslandModePolicy.flags(true, IslandOrientation.LANDSCAPE, false, false, true)
        assertTrue(swipeOnly.summarySwipe)
        assertTrue(swipeOnly.rowMode)
        val undefined = IslandModePolicy.flags(true, IslandOrientation.UNDEFINED, true, true, true)
        assertFalse(undefined.summarySwipe)
    }
}
