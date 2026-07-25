@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.takekazex.hypertweak.ui.theme

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.colorsFromSeed

object MiuixSpec2025Adapter {
    fun createThemeController(
        themeMode: Int,
        useMonet: Boolean,
        seedColor: Int,
        paletteId: Int,
        pureBlackActive: Boolean
    ): ThemeController {
        val mode = when (themeMode) {
            1 -> if (useMonet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
            2 -> if (useMonet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
            else -> if (useMonet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
        }
        val style = paletteStyleFromStored(paletteId).miuixStyle
        val generated = ThemeController(
            colorSchemeMode = mode,
            keyColor = Color(seedColor),
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = style,
            isDark = when (themeMode) { 1 -> false; 2 -> true; else -> null }
        )
        if (!pureBlackActive) return generated
        val dark = if (useMonet) {
            colorsFromSeed(Color(seedColor), ThemeColorSpec.Spec2025, style, true)
        } else {
            generated.darkColors
        }
        val pureBlackMode = when (themeMode) { 1 -> ColorSchemeMode.Light; 2 -> ColorSchemeMode.Dark; else -> ColorSchemeMode.System }
        return ThemeController(
            colorSchemeMode = pureBlackMode,
            lightColors = generated.lightColors,
            darkColors = dark.copy(background = Color.Black, surface = Color.Black),
            keyColor = Color(seedColor),
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = style,
            isDark = when (themeMode) { 1 -> false; 2 -> true; else -> null }
        )
    }

    fun previewColors(seedColor: Int, paletteId: Int, dark: Boolean): List<Color> {
        val colors = colorsFromSeed(
            Color(seedColor),
            ThemeColorSpec.Spec2025,
            paletteStyleFromStored(paletteId).miuixStyle,
            dark
        )
        return listOf(colors.primary, colors.secondary, colors.tertiaryContainer)
    }
}
