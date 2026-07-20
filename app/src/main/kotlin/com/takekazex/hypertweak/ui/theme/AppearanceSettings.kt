package com.takekazex.hypertweak.ui.theme

import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

enum class PaletteStyleOption(
    val persistedId: Int,
    val label: String,
    val miuixStyle: ThemePaletteStyle
) {
    TonalSpot(0, "Tonal Spot", ThemePaletteStyle.TonalSpot), Neutral(1, "Neutral", ThemePaletteStyle.Neutral), Vibrant(2, "Vibrant", ThemePaletteStyle.Vibrant), Expressive(3, "Expressive", ThemePaletteStyle.Expressive), Monochrome(4, "Monochrome", ThemePaletteStyle.Monochrome), Fidelity(5, "Fidelity", ThemePaletteStyle.Fidelity), Content(6, "Content", ThemePaletteStyle.Content), Rainbow(7, "Rainbow", ThemePaletteStyle.Rainbow), FruitSalad(8, "Fruit Salad", ThemePaletteStyle.FruitSalad)
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
    "Device color", "Blue", "Green", "Orange", "Red", "Purple", "Indigo"
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
