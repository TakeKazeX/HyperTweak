package com.takekazex.hypertweak.hook.rules.market

import com.takekazex.hypertweak.settings.contract.MarketSettingsKeys as Keys
import org.junit.Assert.*
import org.junit.Test

class MarketTabFilterTest {
    private val home = Keys.KEY_MARKET_HIDE_TAB_HOME
    private val rank = Keys.KEY_MARKET_HIDE_TAB_RANK
    private val assemble = Keys.KEY_MARKET_HIDE_TAB_ASSEMBLE
    private val mine = Keys.KEY_MARKET_HIDE_TAB_MINE
    private val game = Keys.KEY_MARKET_HIDE_TAB_GAME

    @Test fun exactTagsFollowTheirOwnSwitch() {
        assertTrue(MarketTabFilter.hides("native_market_home", setOf(home)))
        assertTrue(MarketTabFilter.hides("native_market_game", setOf(game)))
        assertTrue(MarketTabFilter.hides("native_market_rank", setOf(rank)))
        assertTrue(MarketTabFilter.hides("software_sub5", setOf(Keys.KEY_MARKET_HIDE_TAB_SOFTWARE)))
        assertTrue(MarketTabFilter.hides("native_market_agent", setOf(Keys.KEY_MARKET_HIDE_TAB_AGENT)))
        assertTrue(MarketTabFilter.hides("native_market_quick_game", setOf(Keys.KEY_MARKET_HIDE_TAB_QUICK_GAME)))
        assertTrue(MarketTabFilter.hides("native_market_shortplay", setOf(Keys.KEY_MARKET_HIDE_TAB_SHORT_PLAY)))
        assertFalse(MarketTabFilter.hides("native_market_home", setOf(game)))
    }

    @Test fun prefixFamiliesUseTheHostClassifiers() {
        assertTrue(MarketTabFilter.hides("native_rank_tab_software", setOf(rank)))
        assertTrue(MarketTabFilter.hides("native_overseas_rank_tab_game", setOf(rank)))
        assertTrue(MarketTabFilter.hides("native_agent_rank_tab_feed", setOf(rank)))
        assertTrue(MarketTabFilter.hides("native_app_assemble_v2", setOf(assemble)))
        assertFalse(MarketTabFilter.hides("native_market_rank_game", setOf(rank)))
        assertFalse(MarketTabFilter.hides("native_market_rank_software", setOf(rank)))
    }

    @Test fun mineAcceptsTheLegacyAlias() {
        assertTrue(MarketTabFilter.hides("native_market_mine", setOf(mine)))
        assertTrue(MarketTabFilter.hides("mine", setOf(mine)))
        assertFalse(MarketTabFilter.hides("native_market_mine", setOf(home)))
    }

    @Test fun unknownAndSubTabsStayVisible() {
        assertFalse(MarketTabFilter.hides("native_market_feature", setOf(home)))
        assertFalse(MarketTabFilter.hides("must-have", setOf(home)))
        assertFalse(MarketTabFilter.hides(null, setOf(home)))
        assertFalse(MarketTabFilter.hides("native_market_home", emptySet()))
    }

    @Test fun removalKeepsTheFirstEntryWhenEverythingMatches() {
        val tags = listOf("native_market_home", "native_market_game", "native_market_mine")
        val all = setOf(home, game, mine)
        assertArrayEquals(intArrayOf(1, 2), MarketTabFilter.removals(tags, all))
        assertArrayEquals(intArrayOf(0, 1), MarketTabFilter.removals(tags, setOf(home, game)))
        assertArrayEquals(intArrayOf(), MarketTabFilter.removals(tags, setOf(rank)))
        assertArrayEquals(intArrayOf(), MarketTabFilter.removals(emptyList(), all))
    }

    @Test fun applyToMutatesTheHostListInPlace() {
        val tabs = mutableListOf("native_market_home", "native_market_game", "native_market_mine")
        assertTrue(MarketTabFilter.applyTo(tabs, setOf(game)) { it as String })
        assertEquals(listOf("native_market_home", "native_market_mine"), tabs)
        assertFalse(MarketTabFilter.applyTo(tabs, setOf(rank)) { it as String })
        assertFalse(MarketTabFilter.applyTo(tabs, emptySet()) { it as String })
    }

    @Test fun onlyTabSwitchesParticipate() {
        val settings = setOf(Keys.KEY_MARKET_SPLASH, Keys.KEY_MARKET_HIDE_TAB_RANK, "unknown")
        assertEquals(setOf(rank), MarketTabFilter.enabledKeys(settings))
    }
}
