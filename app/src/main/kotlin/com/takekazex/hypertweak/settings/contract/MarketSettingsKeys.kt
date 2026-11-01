package com.takekazex.hypertweak.settings.contract

/** All policies default off; keys are shared by UI, backup and the runtime dispatcher. */
object MarketSettingsKeys {
    const val KEY_MARKET_SPLASH = "market_splash"
    const val KEY_MARKET_DETAIL = "market_detail"
    const val KEY_MARKET_VIDEO = "market_video"
    const val KEY_MARKET_SEARCH_SUGGEST = "market_search_suggest"
    const val KEY_MARKET_HOME = "market_home"
    const val KEY_MARKET_UPDATE = "market_update"
    const val KEY_MARKET_MINE = "market_mine"
    const val KEY_MARKET_PUSH_DIALOG = "market_push_dialog"
    const val KEY_MARKET_MARKETING_ICON = "market_marketing_icon"
    const val PACKAGE = "com.xiaomi.market"
    val keys = setOf(
        KEY_MARKET_SPLASH,
        KEY_MARKET_DETAIL,
        KEY_MARKET_VIDEO,
        KEY_MARKET_SEARCH_SUGGEST,
        KEY_MARKET_HOME,
        KEY_MARKET_UPDATE,
        KEY_MARKET_MINE,
        KEY_MARKET_PUSH_DIALOG,
        KEY_MARKET_MARKETING_ICON,
    )
}
