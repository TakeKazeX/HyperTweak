package com.takekazex.hypertweak.hook

data class AppSettingsSnapshot(
    val themeMode: Int,
    val useMonet: Boolean,
    val seedColor: Int,
    val paletteStyle: Int,
    val pureBlackDarkTheme: Boolean,
    val useFloatingBottomBar: Boolean,
    val floatingBarStyle: Int,
    val predictiveBackStyle: Int,
    val predictiveBackFollowGesture: Boolean,
    val pageScale: Float,
    val allowLandscape: Boolean,
    val language: Int
) {
    companion object {
        fun read(): AppSettingsSnapshot = AppSettingsSnapshot(
            themeMode = Preferences.getInt(Preferences.KEY_THEME_MODE, 0),
            useMonet = Preferences.getBoolean(Preferences.KEY_USE_MONET, false),
            seedColor = Preferences.getInt(
                Preferences.KEY_SEED_COLOR,
                Preferences.DEFAULT_SEED_COLOR
            ),
            paletteStyle = Preferences.getInt(Preferences.KEY_THEME_PALETTE_STYLE, 0),
            pureBlackDarkTheme = Preferences.getBoolean(Preferences.KEY_PURE_BLACK_DARK_THEME, false),
            useFloatingBottomBar = Preferences.getBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, false),
            floatingBarStyle = Preferences.getInt(Preferences.KEY_FLOATING_BAR_STYLE, 0),
            predictiveBackStyle = Preferences.getInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, 1),
            predictiveBackFollowGesture = Preferences.getBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true),
            pageScale = Preferences.getFloat(Preferences.KEY_PAGE_SCALE, 1f),
            allowLandscape = Preferences.getBoolean(Preferences.KEY_ALLOW_LANDSCAPE, false),
            language = Preferences.getInt(Preferences.KEY_LANGUAGE, 0)
        )
    }
}
