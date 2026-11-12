package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import com.takekazex.hypertweak.settings.contract.IconTunerContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftCarrierSwapPolicyTest {
    private val native = LeftCarrierSwapPolicy.NATIVE

    @Test fun hostClockOrAlarmNeverHidesTheIcons() {
        // Carrier gone: the host is showing its clock or alarm, which the icons follow in a row.
        for (icons in listOf(false, true)) for (intro in listOf(false, true)) {
            assertEquals(native, LeftCarrierSwapPolicy.resolve(carrierShown = false, iconsShown = icons, introActive = intro))
        }
    }

    @Test fun visibleIconsTakeTheCarrierPlaceAndHandItBack() {
        val icons = LeftCarrierSwapPolicy.resolve(carrierShown = true, iconsShown = true, introActive = false)
        assertEquals(0f, icons.carrierAlpha)
        assertEquals(1f, icons.iconsAlpha)
        val carrier = LeftCarrierSwapPolicy.resolve(carrierShown = true, iconsShown = false, introActive = false)
        assertEquals(1f, carrier.carrierAlpha)
        assertEquals(0f, carrier.iconsAlpha)
    }

    @Test fun introKeepsTheCarrierOverPendingIcons() {
        val target = LeftCarrierSwapPolicy.resolve(carrierShown = true, iconsShown = true, introActive = true)
        assertEquals(1f, target.carrierAlpha)
        assertEquals(0f, target.iconsAlpha)
    }

    @Test fun introOnlyAppliesToItsModeAndWindow() {
        val intro = IconTunerContract.LEFT_KEYGUARD_CARRIER_INTRO
        val replace = IconTunerContract.LEFT_KEYGUARD_CARRIER_REPLACE
        val window = LeftCarrierSwapPolicy.IntroPhase.WINDOW
        assertTrue(LeftCarrierSwapPolicy.introActive(intro, window, now = 500, windowEnd = 1000))
        assertFalse(LeftCarrierSwapPolicy.introActive(intro, window, now = 1000, windowEnd = 1000))
        assertFalse(LeftCarrierSwapPolicy.introActive(replace, window, now = 500, windowEnd = 1000))
    }

    @Test fun screenOffKeepsIconsUntilTheBarIsHiddenThenHoldsTheCarrier() {
        val intro = IconTunerContract.LEFT_KEYGUARD_CARRIER_INTRO
        var phase = LeftCarrierSwapPolicy.IntroPhase.ARMED
        // The bar is still drawn while the screen dims: no carrier flash.
        phase = LeftCarrierSwapPolicy.settle(phase, barShown = true)
        assertFalse(LeftCarrierSwapPolicy.introActive(intro, phase, now = 0, windowEnd = 0))
        // Once hidden, a reveal ahead of the screen-on broadcast still shows the carrier.
        phase = LeftCarrierSwapPolicy.settle(phase, barShown = false)
        phase = LeftCarrierSwapPolicy.settle(phase, barShown = true)
        assertEquals(LeftCarrierSwapPolicy.IntroPhase.HELD, phase)
        assertTrue(LeftCarrierSwapPolicy.introActive(intro, phase, now = Long.MAX_VALUE, windowEnd = 0))
    }

    @Test fun tapChoiceOverridesTheModeButNotAnEmptyIconSet() {
        val shownCarrier = LeftCarrierSwapPolicy.resolve(true, iconsShown = true, introActive = false, manualIcons = false)
        assertEquals(1f, shownCarrier.carrierAlpha)
        val shownIcons = LeftCarrierSwapPolicy.resolve(true, iconsShown = true, introActive = true, manualIcons = true)
        assertEquals(0f, shownIcons.carrierAlpha)
        // With no icon to show, a remembered "icons" tap must not blank the corner.
        val empty = LeftCarrierSwapPolicy.resolve(true, iconsShown = false, introActive = false, manualIcons = true)
        assertEquals(1f, empty.carrierAlpha)
    }

    @Test fun tapFlipsOnlyBetweenTwoDrawableViews() {
        val icons = LeftCarrierSwapPolicy.resolve(true, true, false)
        val carrier = LeftCarrierSwapPolicy.resolve(true, true, true)
        assertEquals(false, LeftCarrierSwapPolicy.toggled(icons, iconsShown = true))
        assertEquals(true, LeftCarrierSwapPolicy.toggled(carrier, iconsShown = true))
        assertEquals(null, LeftCarrierSwapPolicy.toggled(carrier, iconsShown = false))
        assertEquals(null, LeftCarrierSwapPolicy.toggled(native, iconsShown = true))
        assertEquals(null, LeftCarrierSwapPolicy.toggled(null, iconsShown = true))
    }
}
