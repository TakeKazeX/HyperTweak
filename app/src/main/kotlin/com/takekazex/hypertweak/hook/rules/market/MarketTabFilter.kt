package com.takekazex.hypertweak.hook.rules.market

import com.takekazex.hypertweak.settings.contract.MarketSettingsKeys as Keys

/**
 * Pure bottom-bar tag policy. Host types stay outside so the contract is unit testable.
 *
 * The store derives both the tab bar and its pages from `PageConfig.tabs`, so hiding a tab means
 * removing it from the list the host parses. Prefix families follow the host's own classifiers
 * (`native_rank_tab_`, `native_overseas_rank_tab_`, `native_agent_rank_tab_`, `native_app_assemble_`).
 */
internal object MarketTabFilter {
    private const val HOME = "native_market_home"
    private const val GAME = "native_market_game"
    private const val RANK = "native_market_rank"
    private const val SOFTWARE = "software_sub5"
    private const val AGENT = "native_market_agent"
    private const val ASSEMBLE = "native_app_assemble"
    private const val QUICK_GAME = "native_market_quick_game"
    private const val SHORT_PLAY = "native_market_shortplay"
    private const val MINE = "native_market_mine"
    private const val MINE_LEGACY = "mine"

    private val rankPrefixes = arrayOf("native_rank_tab_", "native_overseas_rank_tab_", "native_agent_rank_tab_")
    private const val assemblePrefix = "native_app_assemble_"

    /** Only the tab switches; the purification keys share the same settings set. */
    fun enabledKeys(settings: Set<String>): Set<String> = settings.filterTo(linkedSetOf()) { it in Keys.tabKeys }

    fun hides(tag: String?, enabled: Set<String>): Boolean {
        if (tag == null || enabled.isEmpty()) return false
        return when (tag) {
            HOME -> Keys.KEY_MARKET_HIDE_TAB_HOME in enabled
            GAME -> Keys.KEY_MARKET_HIDE_TAB_GAME in enabled
            RANK -> Keys.KEY_MARKET_HIDE_TAB_RANK in enabled
            SOFTWARE -> Keys.KEY_MARKET_HIDE_TAB_SOFTWARE in enabled
            AGENT -> Keys.KEY_MARKET_HIDE_TAB_AGENT in enabled
            ASSEMBLE -> Keys.KEY_MARKET_HIDE_TAB_ASSEMBLE in enabled
            QUICK_GAME -> Keys.KEY_MARKET_HIDE_TAB_QUICK_GAME in enabled
            SHORT_PLAY -> Keys.KEY_MARKET_HIDE_TAB_SHORT_PLAY in enabled
            MINE, MINE_LEGACY -> Keys.KEY_MARKET_HIDE_TAB_MINE in enabled
            else -> when {
                rankPrefixes.any(tag::startsWith) -> Keys.KEY_MARKET_HIDE_TAB_RANK in enabled
                tag.startsWith(assemblePrefix) -> Keys.KEY_MARKET_HIDE_TAB_ASSEMBLE in enabled
                else -> false
            }
        }
    }

    /** Indices to remove. Never returns every index: the host indexes `tabs.get(0)` unconditionally. */
    fun removals(tags: List<String?>, enabled: Set<String>): IntArray {
        if (enabled.isEmpty() || tags.isEmpty()) return IntArray(0)
        val hidden = tags.indices.filter { hides(tags[it], enabled) }
        return when {
            hidden.isEmpty() -> IntArray(0)
            hidden.size == tags.size -> hidden.drop(1).toIntArray()
            else -> hidden.toIntArray()
        }
    }

    /** Removes matching entries in place; returns true when the list changed. */
    fun applyTo(tabs: MutableList<*>, enabled: Set<String>, tagOf: (Any) -> String?): Boolean {
        if (tabs.isEmpty() || enabled.isEmpty()) return false
        val tags = tabs.map { tab -> tab?.let(tagOf) }
        val indices = removals(tags, enabled)
        if (indices.isEmpty()) return false
        for (index in indices.reversed()) tabs.removeAt(index)
        return true
    }
}
