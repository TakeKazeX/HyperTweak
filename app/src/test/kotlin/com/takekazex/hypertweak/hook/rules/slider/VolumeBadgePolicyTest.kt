package com.takekazex.hypertweak.hook.rules.slider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeBadgePolicyTest {
    @Test fun backgroundMaterialOffFallsBackToTheNeutralCapsule() {
        assertEquals(VolumeBadgeSurface.FLAT, VolumeBadgePolicy.surface(advancedMaterialEffective = false, glassEnabled = false))
        // Bionics power save suppresses glass but not the material itself; see the case below.
        assertEquals(VolumeBadgeSurface.FLAT, VolumeBadgePolicy.surface(advancedMaterialEffective = false, glassEnabled = true))
    }

    @Test fun bionicsMaterialUsesTheGlassToken() {
        assertEquals(VolumeBadgeSurface.GLASS, VolumeBadgePolicy.surface(advancedMaterialEffective = true, glassEnabled = true))
    }

    @Test fun classicMaterialAndSuppressedGlassUseBlendColors() {
        assertEquals(VolumeBadgeSurface.FROST, VolumeBadgePolicy.surface(advancedMaterialEffective = true, glassEnabled = false))
    }

    @Test fun controlCenterMainPageOnlyShowsTheDefaultStyle() {
        assertTrue(VolumeBadgePolicy.visible(expanded = false, inControlCenterMainPage = true, sameStyle = false))
        assertFalse(VolumeBadgePolicy.visible(expanded = false, inControlCenterMainPage = true, sameStyle = true))
        assertFalse(VolumeBadgePolicy.visible(expanded = false, inControlCenterMainPage = false, sameStyle = false))
    }

    @Test fun expandedDialogAlwaysShowsTheBadge() {
        assertTrue(VolumeBadgePolicy.visible(expanded = true, inControlCenterMainPage = false, sameStyle = false))
        assertTrue(VolumeBadgePolicy.visible(expanded = true, inControlCenterMainPage = false, sameStyle = true))
        assertTrue(VolumeBadgePolicy.visible(expanded = true, inControlCenterMainPage = true, sameStyle = true))
    }
}
