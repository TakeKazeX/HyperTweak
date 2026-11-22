package com.takekazex.hypertweak.ui.page.home

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.HotReloadReport
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.chrome.AppUiStyle
import com.takekazex.hypertweak.ui.chrome.BottomBarStyle
import com.takekazex.hypertweak.ui.chrome.LocalAppUiStyle
import com.takekazex.hypertweak.ui.chrome.LocalTopBarTitleAlpha
import com.takekazex.hypertweak.ui.chrome.bottomBarStyle
import com.takekazex.hypertweak.ui.chrome.isAppInDarkTheme
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import com.takekazex.hypertweak.ui.effect.appTextureBlur
import com.takekazex.hypertweak.ui.effect.rememberContentReady
import com.takekazex.hypertweak.ui.liquid.IosLiquidGlassNavigationBar
import com.takekazex.hypertweak.ui.liquid.MainPagerState
import com.takekazex.hypertweak.ui.page.app.SettingsScreenContent
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassNavigationRail
import top.yukonga.miuix.kmp.glass.GlassNavigationRailDefaults
import top.yukonga.miuix.kmp.glass.rememberGlassNavigationRailState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.HomeOutline
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.icon.glass.Tune
import top.yukonga.miuix.kmp.material.EdgeStrokes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

@Composable
fun MainPagerScreen(
    mainPagerState: MainPagerState,
    backdrop: LayerBackdrop,
    moduleActive: Boolean,
    hotReloadAvailable: Boolean,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    pendingRestartScopes: RestartScopeSelection,
    onNavigateToSystemLauncher: () -> Unit,
    onNavigateToSystemUi: () -> Unit,
    onNavigateToPhrase: () -> Unit,
    onNavigateToSms: () -> Unit,
    onNavigateToScreenRecorder: () -> Unit,
    onNavigateToScreenshot: () -> Unit,
    onNavigateToDownloadManager: () -> Unit,
    onNavigateToSecurityCenter: () -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    onNavigateToSuperIsland: () -> Unit,
    onNavigateToPurification: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToScopePrompts: () -> Unit,
    onNavigateToBackupRestore: () -> Unit,
    onNavigateToHiddenFeatures: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
    onNavigateToAospRestore: () -> Unit,
    onNavigateToSystemFramework: () -> Unit,
    onNavigateToGoogleServices: () -> Unit,
    onNavigateToIconTuner: () -> Unit,
    onNavigateToGlassTuner: () -> Unit,
    onNavigateToCameraWatermark: () -> Unit,
    onNavigateToExperimentalFeatures: () -> Unit,
    onNavigateToControlCenterCorner: () -> Unit,
    onNavigateToControlCenterResize: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onNavigateToBatteryInfo: () -> Unit,
    onHotReload: () -> Unit,
    onRestartAllScopes: () -> Unit,
    onRestartScope: (RestartScopeSelection) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val appSettings = LocalAppSettings.current
    val pagerState = mainPagerState.pagerState
    val showNavigationRail = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val uiStyle = LocalAppUiStyle.current
    val navigationItems = listOf(
        NavigationItem(stringResource(R.string.main_tab_home), MiuixIcons.Glass.HomeOutline),
        NavigationItem(stringResource(R.string.main_tab_tweaks), MiuixIcons.Glass.Tune),
        NavigationItem(stringResource(R.string.main_tab_settings), MiuixIcons.Glass.SettingsOutline),
    )
    val contentReady = rememberContentReady()
    val barStyle = bottomBarStyle(uiStyle, appSettings.useFloatingBottomBar, appSettings.floatingBarStyle)

    Scaffold(
        // Hosts the one-shot "updated successfully" snackbar. It lives on the root Scaffold because
        // the app always starts here, so a notice queued before the first frame still has a host.
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!showNavigationRail) {
                MainBottomBar(barStyle, navigationItems, mainPagerState, backdrop)
            }
        },
    ) { padding ->
        val pagerFling = PagerDefaults.flingBehavior(state = pagerState, snapAnimationSpec = PagerNavigationSpringSpec)
        MainPagerLayout(showNavigationRail, navigationItems, mainPagerState, backdrop) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().pagerGestureOverride(
                    pagerState = pagerState,
                    flingBehavior = pagerFling,
                ),
                flingBehavior = pagerFling,
                userScrollEnabled = false,
                pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                // Keep the adjacent tab pre-composed for a smooth swipe without keeping all
                // three tabs alive.
                beyondViewportPageCount = 1
            ) { page ->
                val isCurrent = page == pagerState.currentPage
                when (page) {
                    0 -> {
                        if (isCurrent || contentReady) {
                            HomeScreenContent(
                                padding = padding,
                                moduleActive = moduleActive,
                                hotReloadAvailable = hotReloadAvailable,
                                hotReloading = hotReloading,
                                hotReloadTargets = hotReloadTargets,
                                hotReloadReport = hotReloadReport,
                                pendingRestartScopes = pendingRestartScopes,
                                onNavigateToHiddenFeatures = onNavigateToHiddenFeatures,
                                onNavigateToDeviceInfo = onNavigateToDeviceInfo,
                                onNavigateToBatteryInfo = onNavigateToBatteryInfo,
                                onHotReload = onHotReload,
                                onRestartAllScopes = onRestartAllScopes,
                                onRestartScope = onRestartScope
                            )
                        }
                    }
                    1 -> {
                        if (isCurrent || contentReady) {
                            TweaksScreenContent(
                                padding = padding,
                                onNavigateToSystemLauncher = onNavigateToSystemLauncher,
                                onNavigateToSystemUi = onNavigateToSystemUi,
                                onNavigateToSuperIsland = onNavigateToSuperIsland,
                                onNavigateToPurification = onNavigateToPurification,
                                onNavigateToPhrase = onNavigateToPhrase,
                                onNavigateToSms = onNavigateToSms,
                                onNavigateToScreenRecorder = onNavigateToScreenRecorder,
                                onNavigateToScreenshot = onNavigateToScreenshot,
                                onNavigateToDownloadManager = onNavigateToDownloadManager,
                                onNavigateToSecurityCenter = onNavigateToSecurityCenter,
                                onNavigateToAospRestore = onNavigateToAospRestore,
                                onNavigateToSystemFramework = onNavigateToSystemFramework,
                                onNavigateToGoogleServices = onNavigateToGoogleServices,
                                onNavigateToCameraWatermark = onNavigateToCameraWatermark,
                            )
                        }
                    }
                    2 -> {
                        if (isCurrent || contentReady) {
                            SettingsScreenContent(
                                padding = padding,
                                hideLauncherIcon = hideLauncherIcon,
                                onHideLauncherIconChange = onHideLauncherIconChange,
                                onNavigateToExperimentalFeatures = onNavigateToExperimentalFeatures,
                                themeSummary = listOf(
                                    stringResource(R.string.main_theme_follow_system),
                                    stringResource(R.string.main_theme_light),
                                    stringResource(R.string.main_theme_dark)
                                ).getOrElse(appSettings.themeMode) { stringResource(R.string.main_theme_follow_system) },
                                onNavigateToAppearance = onNavigateToAppearance,
                                onNavigateToScopePrompts = onNavigateToScopePrompts,
                                onNavigateToBackupRestore = onNavigateToBackupRestore,
                                onNavigateToAbout = onNavigateToAbout,
                                onNavigateToAppShortcuts = onNavigateToAppShortcuts,
                            )
                        }
                    }
                }
            }
        }
    }
}


/** The main bottom bar in the style picked in Appearance (see [BottomBarStyle]). */
@Composable
private fun MainBottomBar(
    style: BottomBarStyle,
    navigationItems: List<NavigationItem>,
    mainPagerState: MainPagerState,
    backdrop: LayerBackdrop,
) {
    val blurActive = LocalAppBlurMode.current != AppBlurMode.Off
    val selected = mainPagerState.selectedPage
    when (style) {
        BottomBarStyle.Glass -> {
            val glassItems = remember(navigationItems) {
                navigationItems.map { GlassNavigationItem(icon = it.icon, label = it.label) }
            }
            // Same footprint as the other floating bars: centered, 80% wide, at most 340dp.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                GlassNavigationBar(
                    items = glassItems,
                    selectedIndex = selected,
                    onSelect = mainPagerState::animateToPage,
                    backdrop = if (blurActive) backdrop else null,
                    modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(0.8f),
                )
            }
        }
        BottomBarStyle.IosLiquid -> IosLiquidGlassNavigationBar(
            items = navigationItems,
            selectedIndex = selected,
            onItemClick = mainPagerState::animateToPage,
            isBlurActive = blurActive,
            backdrop = backdrop,
        )
        BottomBarStyle.MiuixFloating -> {
            val stroke = if (isAppInDarkTheme()) EdgeStrokes.MiddleDark else EdgeStrokes.MiddleLight
            FloatingNavigationBar(
                modifier = Modifier.appTextureBlur(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
                    blurRadius = 25f,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surfaceContainer.copy(0.6f))),
                    ),
                    edgeStroke = stroke,
                ),
                color = Color.Transparent,
            ) {
                navigationItems.forEachIndexed { index, item ->
                    FloatingNavigationBarItem(
                        selected = selected == index,
                        onClick = { mainPagerState.animateToPage(index) },
                        icon = item.icon,
                        label = item.label,
                    )
                }
            }
        }
        BottomBarStyle.Miuix -> NavigationBar(
            modifier = Modifier.fillMaxWidth().appTextureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(0.8f))),
                ),
            ),
            color = Color.Transparent,
        ) {
            navigationItems.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = selected == index,
                    onClick = { mainPagerState.animateToPage(index) },
                    icon = item.icon,
                    label = item.label,
                )
            }
        }
    }
}

@Composable
private fun MainPagerLayout(
    showNavigationRail: Boolean,
    navigationItems: List<NavigationItem>,
    mainPagerState: MainPagerState,
    backdrop: LayerBackdrop,
    content: @Composable () -> Unit,
) {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val expandRail = width >= 1200.dp
    val railState = rememberNavigationRailState(
        initialValue = if (expandRail) NavigationRailValue.Expanded else NavigationRailValue.Collapsed,
    )
    LaunchedEffect(expandRail) {
        if (expandRail) railState.expand() else railState.collapse()
    }
    if (showNavigationRail && LocalAppUiStyle.current == AppUiStyle.Os4) {
        GlassRailLayout(navigationItems, mainPagerState, backdrop, expandRail, content)
        return
    }
    Row(Modifier.fillMaxSize()) {
        if (showNavigationRail) {
            NavigationRail(
                state = railState,
                expandContentDescription = stringResource(R.string.main_navigation_rail_expand),
                collapseContentDescription = stringResource(R.string.main_navigation_rail_collapse),
            ) {
                navigationItems.forEachIndexed { index, item ->
                    NavigationRailItem(
                        selected = mainPagerState.selectedPage == index,
                        onClick = { mainPagerState.animateToPage(index) },
                        icon = item.icon,
                        label = item.label,
                    )
                }
            }
        }
        val paneInsets = if (showNavigationRail) {
            // The rail already occupies the start cutout and side navigation-bar insets.
            Modifier.consumeWindowInsets(
                WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Start),
            ).clipToBounds()
        } else {
            Modifier
        }
        Box(
            Modifier.weight(1f).fillMaxHeight().then(paneInsets).then(
                if (LocalAppBlurMode.current != AppBlurMode.Off) Modifier.layerBackdrop(backdrop) else Modifier,
            ),
        ) {
            content()
        }
    }
}

/**
 * OS4 landscape: the upstream glass rail floats over the page. Expanded, it is a side panel and
 * the page moves clear of it; collapsed, it is a capsule over the top bar, whose title fades out
 * while covered. The page is recorded into [backdrop] so the glass samples it.
 */
@Composable
private fun GlassRailLayout(
    navigationItems: List<NavigationItem>,
    mainPagerState: MainPagerState,
    backdrop: LayerBackdrop,
    expandRail: Boolean,
    content: @Composable () -> Unit,
) {
    val railState = rememberGlassNavigationRailState(initialExpanded = expandRail)
    LaunchedEffect(expandRail) { railState.expanded = expandRail }
    LaunchedEffect(railState.expanded) { mainPagerState.snapTransitions = railState.expanded }
    val blurActive = LocalAppBlurMode.current != AppBlurMode.Off
    val glassItems = remember(navigationItems) {
        navigationItems.map { GlassNavigationItem(icon = it.icon, label = it.label) }
    }
    val panelWidth = GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth
    val titleAlpha: () -> Float = remember(railState) { { railState.progress } }
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (blurActive) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val start = (panelWidth * railState.progress).roundToPx().coerceIn(0, constraints.maxWidth)
                        val placeable = measurable.measure(constraints.offset(horizontal = -start))
                        layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(start, 0) }
                    },
            ) {
                CompositionLocalProvider(LocalTopBarTitleAlpha provides titleAlpha) {
                    content()
                }
            }
        }
        GlassNavigationRail(
            items = glassItems,
            selectedIndex = mainPagerState.selectedPage,
            onSelect = mainPagerState::animateToPage,
            backdrop = if (blurActive) backdrop else null,
            state = railState,
            expandContentDescription = stringResource(R.string.main_navigation_rail_expand),
            collapseContentDescription = stringResource(R.string.main_navigation_rail_collapse),
        )
    }
}
