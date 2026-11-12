package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeftKeyguardAirplanePolicyTest {
    private val carrier = LeftCarrierSwapPolicy.resolve(carrierShown = true, iconsShown = false, introActive = false)
    private val icons = LeftCarrierSwapPolicy.resolve(carrierShown = true, iconsShown = true, introActive = false)
    private val native = LeftCarrierSwapPolicy.resolve(carrierShown = false, iconsShown = true, introActive = false)

    private fun at(target: LeftCarrierSwapPolicy.Target?, beside: Boolean = false, iconsShown: Boolean = false,
                   intro: Boolean = false, glyph: Boolean = true, carrierShown: Boolean = true) =
        LeftKeyguardAirplanePolicy.atLabel(glyph, beside, carrierShown, target, iconsShown, intro)

    @Test fun aLabelWithoutGlyphLeavesTheAirplaneNative() {
        assertNull(at(carrier, glyph = false))
        assertNull(at(null))
    }

    @Test fun withLeftIconsTheGlyphFollowsTheCarrierSwap() {
        assertEquals(true, at(carrier, iconsShown = true))
        assertEquals(false, at(icons, iconsShown = true))
        assertEquals(false, at(native, iconsShown = true, carrierShown = false))
    }

    @Test fun duoReceivesEveryGlyphModeAfterTheIntroEvenWithLeftPlacementOff() {
        for (beside in listOf(false, true)) {
            assertEquals(true, LeftKeyguardAirplanePolicy.atLabel(true, beside, true, carrier,
                false, true, duo = true, labelKeepsAirplane = true))
            assertEquals(false, LeftKeyguardAirplanePolicy.atLabel(true, beside, true, carrier,
                false, false, duo = true, labelKeepsAirplane = true))
        }
    }

    @Test fun disablingLeftPlacementKeepsTheLabelWhenNoDuoIsAvailable() {
        assertEquals(true, LeftKeyguardAirplanePolicy.atLabel(true, false, true, carrier,
            false, false, duo = false, labelKeepsAirplane = true))
    }

    @Test fun withoutLeftIconsBesideTextStaysAndReplacementLeavesAfterTheIntro() {
        assertEquals(true, at(carrier, beside = true))
        assertEquals(true, at(carrier, intro = true))
        assertEquals(false, at(carrier))
    }
}
