package com.takekazex.hypertweak.ui.theme

import androidx.annotation.StringRes
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

enum class PaletteStyleOption(
    val persistedId: Int,
    @StringRes val labelRes: Int,
    val miuixStyle: ThemePaletteStyle
) {
    TonalSpot(0, R.string.theme_palette_tonal_spot, ThemePaletteStyle.TonalSpot), Neutral(1, R.string.theme_palette_neutral, ThemePaletteStyle.Neutral), Vibrant(2, R.string.theme_palette_vibrant, ThemePaletteStyle.Vibrant), Expressive(3, R.string.theme_palette_expressive, ThemePaletteStyle.Expressive), Monochrome(4, R.string.theme_palette_monochrome, ThemePaletteStyle.Monochrome), Fidelity(5, R.string.theme_palette_fidelity, ThemePaletteStyle.Fidelity), Content(6, R.string.theme_palette_content, ThemePaletteStyle.Content), Rainbow(7, R.string.theme_palette_rainbow, ThemePaletteStyle.Rainbow), FruitSalad(8, R.string.theme_palette_fruit_salad, ThemePaletteStyle.FruitSalad)
}

val presetAccentColors = listOf(
    0,
    0xFF007AFF.toInt(),
    0xFF4CAF50.toInt(),
    0xFFFF9800.toInt(),
    0xFFF44336.toInt(),
    0xFF9C27B0.toInt(),
    0xFF3F51B5.toInt()
)

val presetAccentLabels = listOf(
    R.string.theme_accent_device,
    R.string.theme_accent_blue,
    R.string.theme_accent_green,
    R.string.theme_accent_orange,
    R.string.theme_accent_red,
    R.string.theme_accent_purple,
    R.string.theme_accent_indigo
)

fun paletteStyleFromStored(value: Int): PaletteStyleOption =
    PaletteStyleOption.entries.firstOrNull { it.persistedId == value } ?: PaletteStyleOption.TonalSpot

fun paletteStyleIndex(value: Int): Int =
    PaletteStyleOption.entries.indexOfFirst { it.persistedId == value }.takeIf { it >= 0 } ?: 0

fun accentSelectionIndex(useMonet: Boolean, seedColor: Int): Int {
    if (!useMonet) return 0
    val presetIndex = presetAccentColors.indexOf(seedColor)
    return if (presetIndex >= 0) presetIndex + 1 else presetAccentColors.size + 1
}

fun accentSeedForSelection(index: Int, currentSeed: Int): Int = when {
    index <= 0 -> currentSeed
    index in 1..presetAccentColors.size -> presetAccentColors[index - 1]
    else -> currentSeed
}

fun isEffectivelyDark(themeMode: Int, systemIsDark: Boolean): Boolean = when (themeMode) {
    1 -> false
    2 -> true
    else -> systemIsDark
}

fun formatSeedColor(seedColor: Int): String = "#%06X".format(seedColor and 0xFFFFFF)
