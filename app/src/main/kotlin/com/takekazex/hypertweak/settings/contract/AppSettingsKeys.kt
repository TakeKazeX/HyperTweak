package com.takekazex.hypertweak.settings.contract

/** Persisted contract; values remain independent of storage and host implementations. */
object AppSettingsKeys {
    const val NAME = "hypertweak_settings"
    const val DEFAULT_SEED_COLOR = 0
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val KEY_APP_BLUR_MODE = "app_blur_mode"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_USE_MONET = "theme_use_monet"
    const val KEY_SEED_COLOR = "theme_seed_color"
    const val KEY_THEME_PALETTE_STYLE = "theme_palette_style"
    const val KEY_PURE_BLACK_DARK_THEME = "pure_black_dark_theme"
    const val KEY_APP_DETAILS_ICON_LAUNCH = "app_details_icon_launch"
    const val KEY_LANGUAGE = "app_language"
    const val KEY_PAGE_SCALE = "page_scale"
    const val KEY_APP_SHORTCUTS = "app_shortcuts"
    const val KEY_APP_SHORTCUTS_ORDER = "app_shortcuts_order"
    const val KEY_LOG_LEVEL = "debug_log_level"
    const val KEY_UPDATE_CHANNEL = "update_channel"
    const val KEY_UPDATE_CHECK_INTERVAL = "update_check_interval"
    const val KEY_UPDATE_PROXY_MODE = "update_proxy_mode"
    const val KEY_UPDATE_PROXY_URL = "update_proxy_url"
}
