package com.takekazex.hypertweak.ui.theme

import androidx.compose.ui.graphics.Color
import com.takekazex.hypertweak.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `dark mode resolution follows explicit mode before system`() {
        assertFalse(isEffectivelyDark(0, false))
        assertTrue(isEffectivelyDark(0, true))
        assertFalse(isEffectivelyDark(1, true))
        assertTrue(isEffectivelyDark(2, false))
    }

    @Test
    fun `palette style drives the controller palette`() {
        val seed = 0xFF336699.toInt()

        // The palette style is carried onto every controller the adapter builds; previews and the
        // applied theme therefore derive from the same style without a separate code path.
        listOf(0, 1, 2).forEach { paletteId ->
            val expected = paletteStyleFromStored(paletteId).miuixStyle
            assertEquals(expected, MiuixSpec2025Adapter.baseController(2, true, seed, paletteId).paletteStyle)
            assertEquals(expected, MiuixSpec2025Adapter.seededMonetController(seed, paletteId, true).paletteStyle)
        }
    }

    @Test
    fun `pure black controller keeps the generated palette and blacks only background and surface`() {
        val seed = 0xFF336699.toInt()
        val generated = MiuixSpec2025Adapter.baseController(2, useMonet = true, seedColor = seed, paletteId = 2)
        val derivedDark = MiuixSpec2025Adapter.seededMonetController(seed, 2, dark = true).darkColors

        val controller = MiuixSpec2025Adapter.pureBlackController(
            generated = generated,
            themeMode = 2,
            seedColor = seed,
            paletteId = 2,
            derivedDark = derivedDark
        )

        assertEquals(derivedDark.primary, controller.darkColors.primary)
        assertEquals(Color.Black, controller.darkColors.background)
        assertEquals(Color.Black, controller.darkColors.surface)
        // Pure black pins the scheme so a system theme flip cannot reintroduce the light palette.
        assertEquals(top.yukonga.miuix.kmp.theme.ColorSchemeMode.Dark, controller.colorSchemeMode)
    }

    @Test
    fun `theme mode selects the matching color scheme`() {
        assertEquals(
            top.yukonga.miuix.kmp.theme.ColorSchemeMode.MonetLight,
            MiuixSpec2025Adapter.colorSchemeModeFor(1, useMonet = true)
        )
        assertEquals(
            top.yukonga.miuix.kmp.theme.ColorSchemeMode.MonetDark,
            MiuixSpec2025Adapter.colorSchemeModeFor(2, useMonet = true)
        )
        assertEquals(
            top.yukonga.miuix.kmp.theme.ColorSchemeMode.MonetSystem,
            MiuixSpec2025Adapter.colorSchemeModeFor(0, useMonet = true)
        )
        assertEquals(
            top.yukonga.miuix.kmp.theme.ColorSchemeMode.System,
            MiuixSpec2025Adapter.colorSchemeModeFor(0, useMonet = false)
        )
        assertEquals(null, MiuixSpec2025Adapter.darkOverride(0))
        assertEquals(false, MiuixSpec2025Adapter.darkOverride(1))
        assertEquals(true, MiuixSpec2025Adapter.darkOverride(2))
    }
}
