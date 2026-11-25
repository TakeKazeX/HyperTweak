package com.takekazex.hypertweak.ui.chrome

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.effect.rememberContentReady
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Gap between the top bar and the first item, and below the last item. */
private val ContentTopGap: Dp = 8.dp
private val ContentBottomGap: Dp = 16.dp

/**
 * The frame every page uses: top bar, scroll behavior, top-bar backdrop, popup host and insets.
 *
 * [content] receives the Scaffold padding (top bar, system bars and cutout on every side). Lay
 * content out *under* the bar using it, never below it, so the bar can collapse and blur over the
 * scrolled content. Most pages use [AppScrollPage] or [AppLazyPage] instead.
 *
 * [backdrop] records only the page content, for the top bar (upstream two-level sampling); the
 * floating overlays sample the whole-page [PageBackdropBox] recording instead. The recording is
 * applied before the rail inset so the bar's blur never gets cut at the rail edge.
 *
 * The backdrop is captured from the first frame in OS4 (glass must never enter as its fallback);
 * OS3 keeps deferring it until the entry transition settles.
 */
@Composable
fun AppPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backContentDescription: String = stringResource(R.string.debug_back),
    subtitle: String = "",
    expandable: Boolean = true,
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    backdrop: LayerBackdrop? = rememberPageBackdrop(),
    contentScrolled: Boolean? = null,
    titleAlpha: (() -> Float)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottom: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val ready = rememberContentReady() || LocalAppUiStyle.current == AppUiStyle.Os4
    val barBackdrop = backdrop?.takeIf { ready }
    CompositionLocalProvider(
        LocalAppOverlayHost provides true,
        LocalTopAppBarBackdrop provides barBackdrop,
    ) {
        Scaffold(
            modifier = modifier,
            topBar = {
                AppTopBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backContentDescription = backContentDescription,
                    subtitle = subtitle,
                    expandable = expandable,
                    contentScrolled = contentScrolled,
                    titleAlpha = titleAlpha,
                    actions = actions,
                    bottomContent = topBarBottom,
                )
            },
            snackbarHost = snackbarHost,
            popupHost = { MiuixPopupHost() },
        ) { padding ->
            Box(Modifier.fillMaxSize().then(if (barBackdrop != null) Modifier.layerBackdrop(barBackdrop) else Modifier)) {
                Box(Modifier.fillMaxSize().sidebarStartInset()) {
                    content(padding)
                }
            }
        }
    }
}

/**
 * A scrolling column page: [AppPage] + gaps, safe horizontal insets and [appPageScroll].
 *
 * @param bottomInset Space taken by chrome outside the page (the main pager's bottom bar); the
 *   last item clears whichever of it and the system bars is taller.
 */
@Composable
fun AppScrollPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backContentDescription: String = stringResource(R.string.debug_back),
    subtitle: String = "",
    scrollState: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    bottomInset: Dp = 0.dp,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottom: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    AppPage(
        title = title,
        onBack = onBack,
        modifier = modifier,
        backContentDescription = backContentDescription,
        subtitle = subtitle,
        scrollBehavior = scrollBehavior,
        actions = actions,
        topBarBottom = topBarBottom,
        snackbarHost = snackbarHost,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction))
                .appPageScroll(scrollBehavior)
                .verticalScroll(scrollState, overscrollEffect = null),
            verticalArrangement = verticalArrangement,
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + ContentTopGap))
            content()
            Spacer(Modifier.height(maxOf(padding.calculateBottomPadding(), bottomInset) + ContentBottomGap))
        }
    }
}

/** A lazy list page: [AppPage] with the same gaps and insets as [AppScrollPage]. */
@Composable
fun AppLazyPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backContentDescription: String = stringResource(R.string.debug_back),
    subtitle: String = "",
    state: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottom: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    AppPage(
        title = title,
        onBack = onBack,
        modifier = modifier,
        backContentDescription = backContentDescription,
        subtitle = subtitle,
        scrollBehavior = scrollBehavior,
        actions = actions,
        topBarBottom = topBarBottom,
        snackbarHost = snackbarHost,
    ) { padding ->
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().appPageScroll(scrollBehavior),
            contentPadding = padding.withGaps(),
            overscrollEffect = null,
            content = content,
        )
    }
}

/** Scaffold padding plus the standard top/bottom gaps, for custom lazy layouts. */
@Composable
fun PaddingValues.withGaps(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding() + ContentTopGap,
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding() + ContentBottomGap,
    )
}

/**
 * Page scroll wiring (upstream example `pageScrollModifiers`): elastic overscroll is mirrored into
 * the bar state so the OS4 glass band and buttons follow the visual content position; the nested
 * scroll connection drives title collapse in both styles. This modifier owns the rebound, so the
 * scrollable under it must pass `overscrollEffect = null` (the Miuix theme would add a second one).
 */
@Composable
fun Modifier.appPageScroll(scrollBehavior: ScrollBehavior): Modifier {
    val state = scrollBehavior.state
    val onOffsetChanged: (Float) -> Unit = remember(state) { { state.overscrollOffset = it } }
    return this
        .overScrollVertical(onOffsetChanged = onOffsetChanged)
        .nestedScroll(scrollBehavior.nestedScrollConnection)
}
