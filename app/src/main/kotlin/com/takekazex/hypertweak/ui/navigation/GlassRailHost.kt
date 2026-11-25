package com.takekazex.hypertweak.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.LocalSearchTopInset
import com.takekazex.hypertweak.ui.chrome.LocalTopBarStartInset
import com.takekazex.hypertweak.ui.chrome.LocalTopBarTitleAlpha
import com.takekazex.hypertweak.ui.chrome.rememberPageBackdrop
import com.takekazex.hypertweak.ui.liquid.MainPagerState
import com.takekazex.hypertweak.ui.page.home.SETTINGS_PAGE
import com.takekazex.hypertweak.ui.page.home.mainNavigationItems
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassNavigationRail
import top.yukonga.miuix.kmp.glass.GlassNavigationRailDefaults
import top.yukonga.miuix.kmp.glass.rememberGlassNavigationRailState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The main settings search entry. OS4 opens the glass overlay on the main pager ([expanded]);
 * OS3 opens the classic search page, so [expanded] stays false there.
 */
@Immutable
internal class MainSearch(
    val expanded: Boolean,
    val open: () -> Unit,
    val dismiss: () -> Unit,
)

/** Clearance below the collapsed rail capsule, which sits over the top bar center (upstream). */
private val CollapsedRailTopObstruction = 56.dp

/**
 * OS4 landscape: the upstream glass rail persists above every page (migration guide §4.1). It sits
 * outside NavDisplay, on top of the recording it samples; NavDisplay stays full screen, and pages
 * move clear of the expanded panel themselves ([LocalTopBarStartInset]). Collapsed, the rail is a
 * capsule over the top bar center: titles fade with [LocalTopBarTitleAlpha] and the glass search
 * bar drops below it ([LocalSearchTopInset]).
 *
 * Secondary pages select nothing; picking a tab from one returns to the main pager without a
 * transition. Search is the rail's secondary item. Root-Scaffold popups and dialogs already draw
 * above the rail, so no extra top layer is needed. [enabled] false keeps the same tree (no rail,
 * no recording), so rotating or switching style never recreates NavDisplay.
 */
@Composable
internal fun GlassRailHost(
    enabled: Boolean,
    mainPagerState: MainPagerState,
    onMain: Boolean,
    search: MainSearch,
    onSelectTab: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val expandRail = width >= 1200.dp
    val railState = rememberGlassNavigationRailState(initialExpanded = expandRail)
    LaunchedEffect(expandRail) { railState.expanded = expandRail }
    LaunchedEffect(enabled, railState.expanded) { mainPagerState.snapTransitions = enabled && railState.expanded }
    val sidebarBackdrop = if (enabled) rememberPageBackdrop() else null
    val panelWidth = GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth
    val titleAlpha: () -> Float = remember(railState, enabled) { if (enabled) { { railState.progress } } else { { 1f } } }
    val startInset: () -> Dp = remember(railState, enabled) {
        if (enabled) { { panelWidth * railState.progress } } else { { 0.dp } }
    }
    val searchTop: () -> Dp = remember(railState, enabled) {
        if (enabled) { { CollapsedRailTopObstruction * (1f - railState.progress) } } else { { 0.dp } }
    }
    Box(Modifier.fillMaxSize().then(if (enabled) Modifier.background(MiuixTheme.colorScheme.surface) else Modifier)) {
        // layerBackdrop first, before any inset, so the rail never samples a cut-off page.
        Box(Modifier.fillMaxSize().then(if (sidebarBackdrop != null) Modifier.layerBackdrop(sidebarBackdrop) else Modifier)) {
            CompositionLocalProvider(
                LocalTopBarTitleAlpha provides titleAlpha,
                LocalTopBarStartInset provides startInset,
                LocalSearchTopInset provides searchTop,
                content = content,
            )
        }
        if (enabled) {
            val items = mainNavigationItems()
            val glassItems = remember(items) { items.map { GlassNavigationItem(icon = it.icon, label = it.label) } }
            val searchLabel = stringResource(R.string.search_title)
            // Same rule as the bottom bars: no search while the Settings tab is shown.
            val showSearch = !(onMain && mainPagerState.selectedPage == SETTINGS_PAGE)
            val secondaryItems = remember(searchLabel, showSearch) {
                if (showSearch) listOf(GlassNavigationItem(icon = MiuixIcons.Glass.Search, label = searchLabel)) else emptyList()
            }
            GlassNavigationRail(
                items = glassItems,
                selectedIndex = when {
                    !onMain -> -1
                    search.expanded -> glassItems.size
                    else -> mainPagerState.selectedPage
                },
                onSelect = { index -> if (index < glassItems.size) onSelectTab(index) else search.open() },
                backdrop = sidebarBackdrop,
                state = railState,
                secondaryItems = secondaryItems,
                expandContentDescription = stringResource(R.string.main_navigation_rail_expand),
                collapseContentDescription = stringResource(R.string.main_navigation_rail_collapse),
            )
        }
    }
}
