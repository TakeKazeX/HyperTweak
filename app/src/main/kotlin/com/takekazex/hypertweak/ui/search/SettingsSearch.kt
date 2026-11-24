package com.takekazex.hypertweak.ui.search

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.takekazex.hypertweak.ui.page.Route
import java.util.Locale

/**
 * One searchable settings row, generated into [SettingsSearchIndex].
 *
 * [tab] is the main-pager tab for [Route.Main], or the in-page tab of a tabbed page; -1 opens the
 * page as it is. String ids are 0 when the row has no page, section or summary text.
 */
internal class SearchEntry(
    val route: Route,
    val tab: Int,
    @param:StringRes val page: Int,
    @param:StringRes val section: Int,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
)

/** A [SearchEntry] with its texts resolved in the current locale. */
internal class SearchItem(
    val entry: SearchEntry,
    val title: String,
    val summary: String,
    val page: String,
    val section: String,
) {
    private val titleKey = title.searchKey()
    private val pathKey = "$page\n$section".searchKey()
    private val summaryKey = summary.searchKey()

    /** Lower is better; null when a query token matches none of the texts. */
    internal fun rank(query: String, tokens: List<String>): Int? {
        if (tokens.any { it !in titleKey && it !in pathKey && it !in summaryKey }) return null
        return when {
            titleKey.startsWith(query) -> 0
            tokens.all { it in titleKey } -> 1
            tokens.all { it in titleKey || it in pathKey } -> 2
            else -> 3
        }
    }
}

/** Results of one page (or main tab), in the order the page's best match ranks. */
internal class SearchGroup(val label: String, val items: List<SearchItem>)

internal object SettingsSearch {

    /**
     * Rows matching every whitespace-separated token of [query] in their title, page, section or
     * summary, grouped by destination. Title matches rank first; ties keep index (page) order.
     */
    fun search(items: List<SearchItem>, query: String): List<SearchGroup> {
        val normalized = query.searchKey()
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val ranked = items.mapIndexedNotNull { index, item ->
            item.rank(normalized, tokens)?.let { Triple(it, index, item) }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        val seen = HashSet<Triple<Route, Int, String>>()
        val groups = LinkedHashMap<Pair<Route, Int>, MutableList<SearchItem>>()
        for ((_, _, item) in ranked) {
            val entry = item.entry
            // Different string ids can render the same row text for one destination.
            if (!seen.add(Triple(entry.route, entry.tab, item.title))) continue
            groups.getOrPut(entry.route to entry.tab) { mutableListOf() } += item
        }
        return groups.values.map { SearchGroup(it.first().page, it) }
    }
}

private val Whitespace = Regex("\\s+")

private fun String.searchKey(): String = trim().lowercase(Locale.ROOT).replace(Whitespace, " ")

/** In-page tab a search result asked a tabbed page to show; the page consumes it. */
internal object SearchLanding {
    private val requests = mutableStateMapOf<Route, Int>()

    fun request(route: Route, tab: Int) {
        if (tab >= 0) requests[route] = tab else requests.remove(route)
    }

    fun pending(route: Route): Int? = requests[route]

    fun consume(route: Route): Int? = requests.remove(route)
}

/**
 * Selected-tab state of a tabbed page that search results can open on a given tab: a fresh page
 * starts on the requested tab, and a page already on the back stack switches to it.
 */
@Composable
internal fun rememberSearchLandingTab(route: Route): MutableIntState {
    val pending = SearchLanding.pending(route)
    val state = rememberSaveable { mutableIntStateOf(pending ?: 0) }
    LaunchedEffect(pending) {
        if (pending != null) {
            state.intValue = pending
            SearchLanding.consume(route)
        }
    }
    return state
}
