package com.takekazex.hypertweak.ui.theme

import androidx.compose.ui.graphics.Color
import com.takekazex.hypertweak.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun `all nine stored palette values map in stable display order`() {
        val expected = listOf(
            R.string.theme_palette_tonal_spot, R.string.theme_palette_neutral, R.string.theme_palette_vibrant,
            R.string.theme_palette_expressive, R.string.theme_palette_monochrome, R.string.theme_palette_fidelity,
            R.string.theme_palette_content, R.string.theme_palette_rainbow, R.string.theme_palette_fruit_salad
        )
        assertEquals(expected, expected.indices.map { paletteStyleFromStored(it).labelRes })
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

    @Test
    fun `palette previews are generated from the selected color style`() {
        val seed = 0xFF336699.toInt()

        val tonalSpot = MiuixSpec2025Adapter.previewColors(seed, 0, dark = false)
        val neutral = MiuixSpec2025Adapter.previewColors(seed, 1, dark = false)
        val vibrant = MiuixSpec2025Adapter.previewColors(seed, 2, dark = false)

        assertNotEquals(tonalSpot, neutral)
        assertNotEquals(tonalSpot, vibrant)
        assertNotEquals(neutral, vibrant)
    }

    @Test
    fun `pure black monet theme keeps generated palette colors`() {
        val seed = 0xFF336699.toInt()
        val expectedPrimary = MiuixSpec2025Adapter.previewColors(seed, 2, dark = true).first()

        val controller = MiuixSpec2025Adapter.createThemeController(
            themeMode = 2,
            useMonet = true,
            seedColor = seed,
            paletteId = 2,
            pureBlackActive = true
        )

        assertEquals(expectedPrimary, controller.darkColors.primary)
        assertEquals(Color.Black, controller.darkColors.background)
        assertEquals(Color.Black, controller.darkColors.surface)
    }
}
