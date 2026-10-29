package com.takekazex.hypertweak.settings.contract

internal object LauncherDesktopKeys {
    const val KEY_ICON_SIZE_PERCENT = "home_icon_size_percent"
    const val KEY_ICON_THEME_MODE = "home_icon_theme_mode"
    const val KEY_ICON_THEME_COLOR = "home_icon_theme_color"
    const val DEFAULT_ICON_COLOR: Int = -4136961 // opaque pale blue
    fun validIconThemeMode(value: Int) = value in 0..4
    const val KEY_GRID = "home_grid_layout"
    fun validGrid(value: Int) = value in 0..12
    fun gridCode(columns: Int, rows: Int): Int {
        require(columns in 4..6 && rows in 5..8)
        return 1 + (columns - 4) * 4 + rows - 5
    }
    fun gridColumns(code: Int): Int { require(code in 1..12); return 4 + (code - 1) / 4 }
    fun gridRows(code: Int): Int { require(code in 1..12); return 5 + (code - 1) % 4 }
    const val FOLLOW_HOST = -1
    fun validPercent(value: Int): Boolean = value == FOLLOW_HOST || value in 50..150
}
