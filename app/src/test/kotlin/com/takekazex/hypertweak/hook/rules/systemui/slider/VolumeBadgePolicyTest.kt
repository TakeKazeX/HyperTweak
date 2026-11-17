package com.takekazex.hypertweak.hook.rules.systemui.slider

import com.takekazex.hypertweak.presentation.slider.SliderPercentageColors
import org.junit.Assert.*
import org.junit.Test

class VolumeBadgePolicyTest {
    @Test fun choicesAreIndependentOfGlobalMaterialMode() {
        assertEquals(VolumeBadgeSurface.FLAT, VolumeBadgePolicy.surface(0))
        assertEquals(VolumeBadgeSurface.FROST, VolumeBadgePolicy.surface(1))
        assertEquals(VolumeBadgeSurface.GLASS, VolumeBadgePolicy.surface(2))
        assertEquals(VolumeBadgeSurface.FLAT, VolumeBadgePolicy.surface(99))
    }

    @Test fun frostUsesThemeSpecificNativeBlendParametersInBothDirections() {
        val light = VolumeBadgePolicy.frostBlend(false)
        val dark = VolumeBadgePolicy.frostBlend(true)
        assertEquals(3, light[1])
        assertEquals(3, dark[1])
        // Both themes retain a translucent blurred sample. The light palette must remain lighter
        // even over a black backdrop; a fixed slider token cannot satisfy this.
        for (background in listOf(0, 128, 255)) {
            fun composite(color: Int): Int {
                val alpha = (color ushr 24) / 255f
                return ((color and 255) * alpha + background * (1f - alpha)).toInt()
            }
            assertTrue(composite(light[0]) > composite(dark[0]))
            assertTrue((light[0] ushr 24) in 1..254)
            assertTrue((dark[0] ushr 24) in 1..254)
        }
        assertArrayEquals(light, VolumeBadgePolicy.frostBlend(false))
        // Callers receive fresh arrays so native consumers cannot corrupt a later theme switch.
        light[0] = 0
        assertNotEquals(0, VolumeBadgePolicy.frostBlend(false)[0])
    }

    @Test fun flatCapsuleFollowsThemeAndTextContrastsWithIt() {
        fun gray(color: Int) = ((color shr 16 and 0xFF) + (color shr 8 and 0xFF) + (color and 0xFF)) / 3
        val light = SliderPercentageColors.popup(false)
        val dark = SliderPercentageColors.popup(true)
        // The host's collapsed colour is dark in both themes; light mode must get a light capsule.
        assertTrue(gray(light.background) > 200)
        assertTrue(gray(dark.background) < 80)
        assertTrue(gray(light.text) < 80)
        assertTrue(gray(dark.text) > 200)
        assertNotEquals(light.stroke, dark.stroke)
    }
}
