package com.takekazex.hypertweak.ui.search

import com.takekazex.hypertweak.ui.page.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun item(route: Route, title: String, summary: String = "", page: String = "", section: String = "", tab: Int = -1) =
        SearchItem(SearchEntry(route, tab, 0, 0, 0, 0), title, summary, page, section)

    private val items = listOf(
        item(Route.SystemUi, "Hide carrier name", summary = "Status bar", page = "System UI", section = "Control center"),
        item(Route.SystemUi, "Clock seconds", page = "System UI", section = "Status bar"),
        item(Route.Screenshot, "Status bar in screenshots", page = "Screenshot"),
        item(Route.Main, "Block spatial audio", page = "Features", section = "Bluetooth", tab = 1),
    )

    private fun titles(query: String) = SettingsSearch.search(items, query).flatMap { g -> g.items.map { it.title } }

    @Test fun blankQueryHasNoResults() {
        assertTrue(SettingsSearch.search(items, "  ").isEmpty())
    }

    @Test fun everyTokenMustMatchSomeText() {
        assertEquals(listOf("Block spatial audio"), titles("bluetooth  SPATIAL"))
        assertTrue(titles("bluetooth carrier").isEmpty())
    }

    @Test fun titleMatchesOutrankPathAndSummaryMatches() {
        // Title prefix, then title, then section, then summary; groups follow their best row.
        assertEquals(
            listOf("Status bar in screenshots", "Clock seconds", "Hide carrier name"),
            titles("status bar"),
        )
        val groups = SettingsSearch.search(items, "status bar")
        assertEquals(listOf("Screenshot", "System UI"), groups.map { it.label })
    }

    @Test fun sameTextForOneDestinationAppearsOnce() {
        val duplicated = items + item(Route.SystemUi, "Clock seconds", page = "System UI")
        assertEquals(1, SettingsSearch.search(duplicated, "clock").single().items.size)
    }

    @Test fun mainPagerTabsAreSeparateGroups() {
        val tabs = listOf(
            item(Route.Main, "Theme", page = "Settings", tab = 2),
            item(Route.Main, "Theme colors", page = "Home", tab = 0),
        )
        assertEquals(2, SettingsSearch.search(tabs, "theme").size)
    }
}
