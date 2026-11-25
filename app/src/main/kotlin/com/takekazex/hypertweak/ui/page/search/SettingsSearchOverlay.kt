package com.takekazex.hypertweak.ui.page.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.LocalSearchTopInset
import com.takekazex.hypertweak.ui.chrome.sidebarStartInset
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import com.takekazex.hypertweak.ui.search.SearchEntry
import com.takekazex.hypertweak.ui.search.SettingsSearch
import top.yukonga.miuix.kmp.glass.GlassSearchOverlay
import top.yukonga.miuix.kmp.glass.GlassSearchOverlayDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Search

/**
 * OS4 settings search: upstream [GlassSearchOverlay] over the main pager (migration guide §7.1).
 * The input and cancel button gain glass once results scroll under them, the result list clears
 * the search bar through a lazily computed top padding, and every query change returns to the top.
 */
@Composable
internal fun SettingsSearchOverlay(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onOpen: (SearchEntry) -> Unit,
) {
    val items = rememberSearchItems()
    val listState = rememberLazyListState()
    val searchTop = LocalSearchTopInset.current
    val topInsets = WindowInsets.systemBars.union(WindowInsets.captionBar).union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top)
    val horizontalInsets = Modifier
        .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
        .sidebarStartInset()
    val searchBarSpace = GlassSearchOverlayDefaults.BarHeight + GlassSearchOverlayDefaults.VerticalPadding * 2
    GlassSearchOverlay(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        scrollMaterialThreshold = GlassSearchOverlayDefaults.VerticalPadding + 6.dp,
        label = stringResource(R.string.search_hint),
        scrollOffset = {
            when {
                listState.layoutInfo.totalItemsCount == 0 -> 0f
                listState.firstVisibleItemIndex > 0 -> Float.MAX_VALUE
                else -> listState.firstVisibleItemScrollOffset.toFloat()
            }
        },
        topInsets = topInsets,
        searchTopPadding = searchTop,
        blurEnabled = LocalAppBlurMode.current != AppBlurMode.Off,
        horizontalInsetsModifier = horizontalInsets,
        searchIcon = MiuixIcons.Glass.Search,
        closeIcon = MiuixIcons.Glass.Close,
    ) { query ->
        val groups = remember(items, query) { SettingsSearch.search(items, query) }
        // Back to the top in the same frame the new results are measured. A LaunchedEffect scroll
        // lands a frame later, and LazyColumn first keeps the old first item's key in place, so a
        // large result change flashed under the search band for one frame.
        val scrolledFor = remember { QueryMark(query) }
        if (scrolledFor.query != query) {
            scrolledFor.query = query
            listState.requestScrollToItem(0)
        }
        val topPadding = topInsets.asPaddingValues()
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues()
        val contentPadding = remember(topPadding, bottomPadding, searchTop, searchBarSpace) {
            object : PaddingValues {
                override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
                override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
                override fun calculateTopPadding(): Dp = topPadding.calculateTopPadding() + searchTop() + searchBarSpace + 8.dp
                override fun calculateBottomPadding(): Dp = bottomPadding.calculateBottomPadding() + 16.dp
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(horizontalInsets),
            contentPadding = contentPadding,
            overscrollEffect = null,
        ) {
            searchResults(query, groups, onOpen)
            item(key = "ime") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime)) }
        }
    }
}

/** Plain (non-snapshot) record of the query the result list was last reset for. */
private class QueryMark(var query: String)
