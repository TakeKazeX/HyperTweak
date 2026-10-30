package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.settings.contract.SliderPercentagePolicy
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import org.junit.Assert.*
import org.junit.Test

class SliderPercentagePolicyTest {
    @Test fun controlCenterValuesAndPopupScopeRemainIndependentAcrossAllChoices() {
        for (display in 0..3) for (values in 0..2) for (color in listOf(false, true)) for (position in 0..1) {
            val policy = SliderPercentagePolicy(display, values, color, position)
            val cc = display == 1 || display == 3
            val popup = display == 2 || display == 3
            assertEquals(cc && values != 1, policy.controlCenter(true))
            assertEquals(cc && values != 0, policy.controlCenter(false))
            assertEquals(cc && values != 0, policy.volumePanel(true))
            assertEquals(popup, policy.volumePanel(false))
            assertEquals(!popup, policy.suppressVolumePanel(false))
            assertEquals(!(cc && values != 0), policy.suppressVolumePanel(true))
            assertEquals(popup && position == 0, policy.innerVolume(false, false))
            assertEquals(popup && position == 1, policy.outerVolume(false, false))
            assertEquals(popup, policy.innerVolume(true, false))
            assertFalse(policy.outerVolume(true, false))
            assertFalse(policy.outerVolume(false, true))
        }
    }

    @Test fun brightnessOnlyCannotSilenceAnEnabledVolumePopup() {
        val popupOnly = SliderPercentagePolicy(display = 2, values = 0)
        assertFalse(popupOnly.controlCenter(true))
        assertFalse(popupOnly.controlCenter(false))
        assertTrue(popupOnly.volumePanel(false))
        assertTrue(popupOnly.outerVolume(expanded = false, inControlCenter = false))
        val both = popupOnly.copy(display = 3)
        assertTrue(both.controlCenter(true))
        assertFalse(both.controlCenter(false))
        assertTrue(both.volumePanel(false))
        // Changing display scope retains the user's control-center value selection.
        assertEquals(0, popupOnly.copy(display = 1).values)
        assertFalse(both.copy(display = 0).volumePanel(false))
    }

    @Test fun scopeSwitchSuppressesExistingPopupAndReleasesItWhenReenabled() {
        val both = SliderPercentagePolicy(display = 3)
        assertFalse(both.suppressVolumePanel(false))
        val ccOnly = both.copy(display = 1)
        assertTrue(ccOnly.suppressVolumePanel(false))
        assertFalse(ccOnly.suppressVolumePanel(true))
        assertFalse(ccOnly.copy(display = 2).suppressVolumePanel(false))
        // Master off must also hide native super-volume values such as 200%.
        assertTrue(ccOnly.copy(display = 0).suppressVolumePanel(false))
        assertTrue(ccOnly.copy(display = 0).suppressVolumePanel(true))
    }

    private fun read(legacy: Boolean, explicit: Int? = null): SliderPercentagePolicy = SliderPercentagePolicy.read(
        { key, default -> if (key == SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE) legacy else default },
        { key, default -> if (key == SystemUiSettingsKeys.KEY_SLIDER_DISPLAY_MODE) explicit ?: default else default }
    )

    @Test fun legacyEnableMigratesAndExplicitOffWins() {
        assertEquals(SliderPercentagePolicy(), read(false))
        assertEquals(SliderPercentagePolicy(display = 3), read(true))
        assertEquals(0, read(true, 0).display)
        assertEquals(1, read(true, 1).display)
        assertEquals(3, read(false, 3).display)
    }

    @Test fun explicitPopupStyleSurvivesIndependentColorAndPlacementChanges() {
        for (style in 0..2) {
            val policy = SliderPercentagePolicy.read({ _, default -> default }, { key, default ->
                if (key == SystemUiSettingsKeys.KEY_SLIDER_POPUP_STYLE) style else default
            })
            assertEquals(style, policy.popupStyle)
            assertEquals(style, policy.copy(unifiedColor = true, popupPosition = 0).popupStyle)
        }
    }

    @Test fun resetReusesDefaultsAndRetainsLegacyFallbackOnlyWhenPresent() {
        assertEquals(SliderPercentagePolicy(), SliderPercentagePolicy.read({ _, default -> default }, { _, default -> default }))
        val invalid = SliderPercentagePolicy.read({ _, default -> default }, { _, _ -> 99 })
        assertEquals(3, invalid.display)
        assertEquals(2, invalid.values)
        assertEquals(1, invalid.popupPosition)
        assertEquals(2, invalid.popupStyle)
    }
}
