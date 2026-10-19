package com.takekazex.hypertweak.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Builds the Spec 2025 [ThemeController], including the pure-black variant.
 *
 * Miuix keeps the seed-to-palette derivation (colorsFromSeed) internal, so a custom palette
 * cannot be produced directly. [ThemeController.currentColors] is public and resolves a Monet
 * scheme by running exactly that derivation, so a throwaway Monet controller exposes the same
 * colours without reaching into library internals.
 */
object MiuixSpec2025Adapter {
    @Composable
    fun rememberThemeController(
        themeMode: Int,
        useMonet: Boolean,
        seedColor: Int,
        paletteId: Int,
        pureBlackActive: Boolean
    ): ThemeController {
        val generated = remember(themeMode, useMonet, seedColor, paletteId) {
            baseController(themeMode, useMonet, seedColor, paletteId)
        }
        if (!pureBlackActive) return generated
        // Resolved through the public composable so the derivation stays Miuix own.
        val seededDark = remember(seedColor, paletteId) {
            seededMonetController(seedColor, paletteId, dark = true)
        }
        val derivedDark = if (useMonet) seededDark.currentColors() else generated.darkColors
        return remember(generated, derivedDark, themeMode) {
            pureBlackController(generated, themeMode, seedColor, paletteId, derivedDark)
        }
    }

    /** Swatches for the palette picker; same derivation as the applied theme. */
    @Composable
    fun previewColors(seedColor: Int, paletteId: Int, dark: Boolean): List<Color> {
        val controller = remember(seedColor, paletteId, dark) {
            seededMonetController(seedColor, paletteId, dark)
        }
        val colors = controller.currentColors()
        return listOf(colors.primary, colors.secondary, colors.tertiaryContainer)
    }

    /** The unmodified controller for the current mode and palette. */
    fun baseController(
        themeMode: Int,
        useMonet: Boolean,
        seedColor: Int,
        paletteId: Int
    ): ThemeController = ThemeController(
        colorSchemeMode = colorSchemeModeFor(themeMode, useMonet),
        keyColor = Color(seedColor),
        colorSpec = ThemeColorSpec.Spec2025,
        paletteStyle = paletteStyleFromStored(paletteId).miuixStyle,
        isDark = darkOverride(themeMode)
    )

    /** A Monet controller used only to read the generated palette through the public API. */
    fun seededMonetController(seedColor: Int, paletteId: Int, dark: Boolean): ThemeController =
        ThemeController(
            colorSchemeMode = if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
            keyColor = Color(seedColor),
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = paletteStyleFromStored(paletteId).miuixStyle
        )

    /**
     * Replaces background and surface with pure black while keeping the generated palette.
     * [derivedDark] is the Monet palette when Monet is active, otherwise the stock dark scheme.
     */
    fun pureBlackController(
        generated: ThemeController,
        themeMode: Int,
        seedColor: Int,
        paletteId: Int,
        derivedDark: Colors
    ): ThemeController = ThemeController(
        colorSchemeMode = fixedColorSchemeMode(themeMode),
        lightColors = generated.lightColors,
        darkColors = derivedDark.copy(background = Color.Black, surface = Color.Black),
        keyColor = Color(seedColor),
        colorSpec = ThemeColorSpec.Spec2025,
        paletteStyle = paletteStyleFromStored(paletteId).miuixStyle,
        isDark = darkOverride(themeMode)
    )

    fun colorSchemeModeFor(themeMode: Int, useMonet: Boolean): ColorSchemeMode = when (themeMode) {
        1 -> if (useMonet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        2 -> if (useMonet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        else -> if (useMonet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    }

    fun fixedColorSchemeMode(themeMode: Int): ColorSchemeMode = when (themeMode) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }

    fun darkOverride(themeMode: Int): Boolean? = when (themeMode) { 1 -> false; 2 -> true; else -> null }
}
