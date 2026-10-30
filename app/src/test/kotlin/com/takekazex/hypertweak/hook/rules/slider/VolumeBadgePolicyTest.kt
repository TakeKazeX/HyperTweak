package com.takekazex.hypertweak.hook.rules.slider

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

    @Test fun fallbackPaletteAndTextFollowBothThemes() {
        val light = VolumeBadgePolicy.palette(false)
        val dark = VolumeBadgePolicy.palette(true)
        assertEquals(0xFFE5E5E5.toInt(), light.background)
        assertEquals(0xFF3A3A3C.toInt(), dark.background)
        assertEquals(0xB3000000.toInt(), light.text)
        assertEquals(0xB3FFFFFF.toInt(), dark.text)
        assertNotEquals(light.stroke, dark.stroke)
    }
}
