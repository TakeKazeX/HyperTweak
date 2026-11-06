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

    /** Bottom-bar tabs; each switch hides one tab and the host hides the bar when one remains. */
    const val KEY_MARKET_HIDE_TAB_HOME = "market_hide_tab_home"
    const val KEY_MARKET_HIDE_TAB_GAME = "market_hide_tab_game"
    const val KEY_MARKET_HIDE_TAB_RANK = "market_hide_tab_rank"
    const val KEY_MARKET_HIDE_TAB_SOFTWARE = "market_hide_tab_software"
    const val KEY_MARKET_HIDE_TAB_AGENT = "market_hide_tab_agent"
    const val KEY_MARKET_HIDE_TAB_ASSEMBLE = "market_hide_tab_assemble"
    const val KEY_MARKET_HIDE_TAB_QUICK_GAME = "market_hide_tab_quick_game"
    const val KEY_MARKET_HIDE_TAB_SHORT_PLAY = "market_hide_tab_short_play"
    const val KEY_MARKET_HIDE_TAB_MINE = "market_hide_tab_mine"

    const val PACKAGE = "com.xiaomi.market"
    val tabKeys = setOf(
        KEY_MARKET_HIDE_TAB_HOME,
        KEY_MARKET_HIDE_TAB_GAME,
        KEY_MARKET_HIDE_TAB_RANK,
        KEY_MARKET_HIDE_TAB_SOFTWARE,
        KEY_MARKET_HIDE_TAB_AGENT,
        KEY_MARKET_HIDE_TAB_ASSEMBLE,
        KEY_MARKET_HIDE_TAB_QUICK_GAME,
        KEY_MARKET_HIDE_TAB_SHORT_PLAY,
        KEY_MARKET_HIDE_TAB_MINE,
    )
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
    ) + tabKeys
}
