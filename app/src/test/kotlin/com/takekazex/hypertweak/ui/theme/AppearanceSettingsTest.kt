package com.takekazex.hypertweak.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun `all nine stored palette values map in stable display order`() {
        val expected = listOf(
            "Tonal Spot", "Neutral", "Vibrant", "Expressive", "Monochrome",
            "Fidelity", "Content", "Rainbow", "Fruit Salad"
        )
        assertEquals(expected, expected.indices.map { paletteStyleFromStored(it).label })
    }

    @Test
    fun `invalid palette values fall back to tonal spot`() {
        assertEquals(PaletteStyleOption.TonalSpot, paletteStyleFromStored(-1))
        assertEquals(PaletteStyleOption.TonalSpot, paletteStyleFromStored(99))
        assertEquals(0, paletteStyleIndex(99))
    }

    @Test
    fun `monet selection preserves off and custom seeds`() {
        val custom = 0xFF123456.toInt()
        assertEquals(0, accentSelectionIndex(false, custom))
        assertEquals(presetAccentColors.size + 1, accentSelectionIndex(true, custom))
        assertEquals(custom, accentSeedForSelection(0, custom))
        assertEquals(custom, accentSeedForSelection(99, custom))
    }

    @Test
    fun `preset and device seeds map to dropdown entries`() {
        presetAccentColors.forEachIndexed { index, seed ->
            assertEquals(index + 1, accentSelectionIndex(true, seed))
            assertEquals(seed, accentSeedForSelection(index + 1, 123))
        }
    }

    @Test
    fun `dark mode resolution follows explicit mode before system`() {
        assertFalse(isEffectivelyDark(0, false))
        assertTrue(isEffectivelyDark(0, true))
        assertFalse(isEffectivelyDark(1, true))
        assertTrue(isEffectivelyDark(2, false))
    }
}
