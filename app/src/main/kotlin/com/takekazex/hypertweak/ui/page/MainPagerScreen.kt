package com.takekazex.hypertweak.ui.page

import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection

import com.takekazex.hypertweak.ui.effect.appTextureBlur
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import com.takekazex.hypertweak.ui.liquid.IosLiquidGlassNavigationBar
import com.takekazex.hypertweak.ui.effect.rememberContentReady
import com.takekazex.hypertweak.util.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.NavigationBar
import com.takekazex.hypertweak.hook.HotReloadReport
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainPagerScreen(
    mainPagerState: com.takekazex.hypertweak.ui.liquid.MainPagerState,
    useFloatingBottomBar: Boolean,
    floatingBarStyle: Int,
    backdrop: LayerBackdrop,
    moduleActive: Boolean,
    hotReloadAvailable: Boolean,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    pendingRestartScopes: RestartScopeSelection,
    paModelSpoofEnabled: Boolean,
    onPaModelSpoofEnabledChange: (Boolean) -> Unit,
    onNavigateToSystemUi: () -> Unit,
    onNavigateToDownloadManager: () -> Unit,
    onNavigateToSecurityCenter: () -> Unit,
    showInSettings: Boolean,
    onShowInSettingsChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    disableVideoRingback: Boolean,
    onDisableVideoRingbackChange: (Boolean) -> Unit,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    useMonet: Boolean,
    onUseMonetChange: (Boolean) -> Unit,
    seedColorHex: Int,
    onSeedColorChange: (Int) -> Unit,
    onUseFloatingBottomBarChange: (Boolean) -> Unit,
    onFloatingBarStyleChange: (Int) -> Unit,
    predictiveBackStyle: Int,
    onPredictiveBackStyleChange: (Int) -> Unit,
    predictiveBackFollowGesture: Boolean,
    onPredictiveBackFollowGestureChange: (Boolean) -> Unit,
    allowLandscape: Boolean,
    onAllowLandscapeChange: (Boolean) -> Unit,
    pageScale: Float,
    onPageScaleChange: (Float) -> Unit,
    unlockThirdPartyDarkMode: Boolean,
    onUnlockThirdPartyDarkModeChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,
    disableMiTrustRiskMonitoring: Boolean,
    onDisableMiTrustRiskMonitoringChange: (Boolean) -> Unit,
    disableGuardEnvironmentCheck: Boolean,
    onDisableGuardEnvironmentCheckChange: (Boolean) -> Unit,
    blockGuardUploadAppList: Boolean,
    onBlockGuardUploadAppListChange: (Boolean) -> Unit,
    blockMiLinkHpplayFiles: Boolean,
    onBlockMiLinkHpplayFilesChange: (Boolean) -> Unit,
    allowThirdPartyTheme: Boolean,
    onAllowThirdPartyThemeChange: (Boolean) -> Unit,
    focusNotificationUnlockWhitelist: Boolean,
    onFocusNotificationUnlockWhitelistChange: (Boolean) -> Unit,
    mediaSuperIslandUnlockWhitelist: Boolean,
    onMediaSuperIslandUnlockWhitelistChange: (Boolean) -> Unit,
    xmsfUnlockFocusAuth: Boolean,
    onXmsfUnlockFocusAuthChange: (Boolean) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToScopePrompts: () -> Unit,
    onNavigateToBackupRestore: () -> Unit,
    onNavigateToHiddenFeatures: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
    onNavigateToAospRestore: () -> Unit,
    onNavigateToGoogleServices: () -> Unit,
    onNavigateToIconTuner: () -> Unit,
    onNavigateToGlassTuner: () -> Unit,
    onNavigateToCameraWatermark: () -> Unit,
    onNavigateToExperimentalFeatures: () -> Unit,
    onNavigateToControlCenterCorner: () -> Unit,
    onNavigateToControlCenterResize: () -> Unit,
    onNavigateToBatteryInfo: () -> Unit,
    onHotReload: () -> Unit,
    onRestartAllScopes: () -> Unit,
    onRestartScope: (RestartScopeSelection) -> Unit,
    snackbarHostState: SnackbarHostState,
    appLanguage: Int,
    onAppLanguageChange: (Int) -> Unit
) {
    val pagerState = mainPagerState.pagerState
    val contentReady = rememberContentReady()
    val isDark = isSystemInDarkTheme()
    val floatingBarShape = RoundedCornerShape(top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults.CornerRadius)
    val floatingHighlight = remember(isDark) {
        if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    }

    Scaffold(
        // Hosts the one-shot "updated successfully" snackbar. It lives on the root Scaffold because
        // the app always starts here, so a notice queued before the first frame still has a host.
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (useFloatingBottomBar) {
                if (floatingBarStyle == 1) {
                    val items = listOf(
                        top.yukonga.miuix.kmp.basic.NavigationItem(stringResource(R.string.main_tab_home), Icons.Rounded.Home),
                        top.yukonga.miuix.kmp.basic.NavigationItem(stringResource(R.string.main_tab_tweaks), Icons.Rounded.Extension),
                        top.yukonga.miuix.kmp.basic.NavigationItem(stringResource(R.string.main_tab_settings), Icons.Rounded.Settings)
                    )
                    IosLiquidGlassNavigationBar(
                        items = items,
                        selectedIndex = mainPagerState.selectedPage,
                        onItemClick = mainPagerState::animateToPage,
                        isBlurActive = LocalAppBlurMode.current != AppBlurMode.Off,
                        backdrop = backdrop
                    )
                } else {
                    FloatingNavigationBar(
                        modifier = Modifier.appTextureBlur(
                            backdrop = backdrop,
                            gradient = ProgressiveBlur.Bottom,
                            shape = floatingBarShape,
                            blurRadius = 25f,
                            colors = BlurDefaults.blurColors(
                                blendColors = listOf(
                                    BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f)),
                                ),
                            ),
                            highlight = floatingHighlight,
                        ),
                        color = Color.Transparent,
                    ) {
                        MyFloatingNavigationBarItem(
                            selected = mainPagerState.selectedPage == 0,
                            onClick = {
                                mainPagerState.animateToPage(0)
                            },
                            icon = Icons.Rounded.Home,
                            label = stringResource(R.string.main_tab_home),
                            iconSize = 32.dp
                        )
                        MyFloatingNavigationBarItem(
                            selected = mainPagerState.selectedPage == 1,
                            onClick = {
                                mainPagerState.animateToPage(1)
                            },
                            icon = Icons.Rounded.Extension,
                            label = stringResource(R.string.main_tab_tweaks),
                            iconSize = 28.0.dp
                        )
                        MyFloatingNavigationBarItem(
                            selected = mainPagerState.selectedPage == 2,
                            onClick = {
                                mainPagerState.animateToPage(2)
                            },
                            icon = Icons.Rounded.Settings,
                            label = stringResource(R.string.main_tab_settings),
                            iconSize = 26.5.dp
                        )
                    }
                }
            } else {
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .appTextureBlur(
                            backdrop = backdrop,
                            gradient = ProgressiveBlur.Bottom,
                            shape = RectangleShape,
                            blurRadius = 25f,
                            colors = BlurDefaults.blurColors(
                                blendColors = listOf(
                                    BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
                                ),
                            ),
                        ),
                    color = Color.Transparent
                ) {
                    MyNavigationBarItem(
                        selected = mainPagerState.selectedPage == 0,
                        onClick = {
                            mainPagerState.animateToPage(0)
                        },
                        icon = Icons.Rounded.Home,
                        label = stringResource(R.string.main_tab_home),
                        iconSize = 30.dp
                    )
                    MyNavigationBarItem(
                        selected = mainPagerState.selectedPage == 1,
                        onClick = {
                            mainPagerState.animateToPage(1)
                        },
                        icon = Icons.Rounded.Extension,
                        label = stringResource(R.string.main_tab_tweaks),
                        iconSize = 26.0.dp
                    )
                    MyNavigationBarItem(
                        selected = mainPagerState.selectedPage == 2,
                        onClick = {
                            mainPagerState.animateToPage(2)
                        },
                        icon = Icons.Rounded.Settings,
                        label = stringResource(R.string.main_tab_settings),
                        iconSize = 24.5.dp
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().then(
            if (LocalAppBlurMode.current != AppBlurMode.Off) Modifier.layerBackdrop(backdrop) else Modifier
        )) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().pagerGestureOverride(
                    pagerState = pagerState,
                    mode = PagerInterceptionMode.CrossAxisInterceptor,
                ),
                userScrollEnabled = false,
                pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                // 1 keeps the adjacent tab pre-composed for a smooth swipe without keeping ALL three
                // tabs alive: with 2, Home's scope-prompt scans kept running while the user sat on
                // the Tweaks/Settings tabs.
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
                                packageName = "com.takekazex.hypertweak",
                                targetSdk = 37,
                                backdrop = backdrop,
                                pendingRestartScopes = pendingRestartScopes,
                                onNavigateToHiddenFeatures = onNavigateToHiddenFeatures,
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
                                onNavigateToSystemUi = onNavigateToSystemUi,
                                onNavigateToDownloadManager = onNavigateToDownloadManager,
                                onNavigateToSecurityCenter = onNavigateToSecurityCenter,
                                onNavigateToAospRestore = onNavigateToAospRestore,
                                onNavigateToGoogleServices = onNavigateToGoogleServices,
                                onNavigateToCameraWatermark = onNavigateToCameraWatermark,
                                paModelSpoofEnabled = paModelSpoofEnabled,
                                onPaModelSpoofEnabledChange = onPaModelSpoofEnabledChange,
                                unlockThirdPartyDarkMode = unlockThirdPartyDarkMode,
                                onUnlockThirdPartyDarkModeChange = onUnlockThirdPartyDarkModeChange,
                                disableVideoRingback = disableVideoRingback,
                                onDisableVideoRingbackChange = onDisableVideoRingbackChange,
                                disableSpatialAudio = disableSpatialAudio,
                                onDisableSpatialAudioChange = onDisableSpatialAudioChange,
                                forceAdaptiveAnc = forceAdaptiveAnc,
                                onForceAdaptiveAncChange = onForceAdaptiveAncChange,
                                disableMiTrustRiskMonitoring = disableMiTrustRiskMonitoring,
                                onDisableMiTrustRiskMonitoringChange = onDisableMiTrustRiskMonitoringChange,
                                disableGuardEnvironmentCheck = disableGuardEnvironmentCheck,
                                onDisableGuardEnvironmentCheckChange = onDisableGuardEnvironmentCheckChange,
                                blockGuardUploadAppList = blockGuardUploadAppList,
                                onBlockGuardUploadAppListChange = onBlockGuardUploadAppListChange,
                                blockMiLinkHpplayFiles = blockMiLinkHpplayFiles,
                                onBlockMiLinkHpplayFilesChange = onBlockMiLinkHpplayFilesChange,
                                allowThirdPartyTheme = allowThirdPartyTheme,
                                onAllowThirdPartyThemeChange = onAllowThirdPartyThemeChange,
                                focusNotificationUnlockWhitelist = focusNotificationUnlockWhitelist,
                                onFocusNotificationUnlockWhitelistChange = onFocusNotificationUnlockWhitelistChange,
                                mediaSuperIslandUnlockWhitelist = mediaSuperIslandUnlockWhitelist,
                                onMediaSuperIslandUnlockWhitelistChange = onMediaSuperIslandUnlockWhitelistChange,
                                xmsfUnlockFocusAuth = xmsfUnlockFocusAuth,
                                onXmsfUnlockFocusAuthChange = onXmsfUnlockFocusAuthChange,
                                backdrop = backdrop
                            )
                        }
                    }
                    2 -> {
                        if (isCurrent || contentReady) {
                            SettingsScreenContent(
                                padding = padding,
                                showInSettings = showInSettings,
                                onShowInSettingsChange = onShowInSettingsChange,
                                hideLauncherIcon = hideLauncherIcon,
                                onHideLauncherIconChange = onHideLauncherIconChange,
                                onNavigateToExperimentalFeatures = onNavigateToExperimentalFeatures,
                                themeSummary = listOf(
                                    stringResource(R.string.main_theme_follow_system),
                                    stringResource(R.string.main_theme_light),
                                    stringResource(R.string.main_theme_dark)
                                ).getOrElse(themeMode) { stringResource(R.string.main_theme_follow_system) },
                                onNavigateToAppearance = onNavigateToAppearance,
                                onNavigateToScopePrompts = onNavigateToScopePrompts,
                                onNavigateToBackupRestore = onNavigateToBackupRestore,
                                allowLandscape = allowLandscape,
                                onAllowLandscapeChange = onAllowLandscapeChange,
                                onNavigateToAbout = onNavigateToAbout,
                                onNavigateToAppShortcuts = onNavigateToAppShortcuts,
                                backdrop = backdrop,
                                appLanguage = appLanguage,
                                onAppLanguageChange = onAppLanguageChange
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.MyNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    iconSize: Dp? = null,
    enabled: Boolean = true,
    selectedContentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    unselectedContentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
) {
    val itemHeight = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.ItemHeight
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val tint = navigationItemTint(selected, isPressed, selectedContentColor, unselectedContentColor)
    val fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
    val mode = top.yukonga.miuix.kmp.basic.LocalNavigationBarDisplayMode.current

    val customIconSize = iconSize ?: top.yukonga.miuix.kmp.basic.NavigationBarDefaults.IconSize

    Column(
        modifier = modifier
            // Min-height instead of a fixed height so the label can grow at large font scales
            // without clipping; at the default scale the content fits and the height is unchanged.
            .heightIn(min = itemHeight)
            .weight(1f)
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (mode == top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode.IconAndText || mode == top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode.IconWithSelectedLabel) Arrangement.Top else Arrangement.Center,
    ) {
        when (mode) {
            top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode.IconAndText -> {
                Box(
                    modifier = Modifier
                        .padding(top = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.IconTopPadding)
                        .size(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        modifier = Modifier.size(customIconSize),
                        imageVector = icon,
                        contentDescription = label,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                    )
                }
                top.yukonga.miuix.kmp.basic.Text(
                    modifier = Modifier.padding(bottom = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.BottomPadding),
                    text = label,
                    color = tint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.LabelFontSize,
                    fontWeight = fontWeight,
                )
            }

            top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode.IconWithSelectedLabel -> {
                val defaultPadding = (itemHeight - 30.dp) / 2
                val iconTopPadding by animateDpAsState(
                    targetValue = if (selected) top.yukonga.miuix.kmp.basic.NavigationBarDefaults.IconTopPadding else defaultPadding,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 300),
                    label = "iconTopPadding",
                )
                val textAlpha by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 300),
                    label = "textAlpha",
                )

                Box(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val topPaddingPx = iconTopPadding.roundToPx()
                            val placeable = measurable.measure(constraints.offset(vertical = -topPaddingPx))
                            layout(placeable.width, placeable.height + topPaddingPx) {
                                placeable.placeRelative(0, topPaddingPx)
                            }
                        }
                        .size(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        modifier = Modifier.size(customIconSize),
                        imageVector = icon,
                        contentDescription = label,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                    )
                }
                top.yukonga.miuix.kmp.basic.Text(
                    modifier = Modifier
                        .padding(bottom = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.BottomPadding)
                        .graphicsLayer { alpha = textAlpha },
                    text = label,
                    color = tint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.LabelFontSize,
                    fontWeight = fontWeight,
                )
            }

            else -> {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        modifier = Modifier.size(customIconSize),
                        imageVector = icon,
                        contentDescription = label,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                    )
                }
            }
        }
    }
}

@Composable
fun MyFloatingNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    iconSize: Dp? = null,
    enabled: Boolean = true,
    selectedContentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    unselectedContentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val tint = navigationItemTint(selected, isPressed, selectedContentColor, unselectedContentColor)

    val customIconSize = iconSize ?: top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults.IconSize

    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(
                    vertical = top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults.IconPadding,
                    horizontal = top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults.IconPadding,
                )
                .size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                modifier = Modifier.size(customIconSize),
                imageVector = icon,
                contentDescription = label,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            )
        }
    }
}

/** Matches Miuix 0.9.4: interaction opacity multiplies the supplied color's alpha. */
internal fun navigationItemTint(
    selected: Boolean,
    pressed: Boolean,
    selectedColor: Color,
    unselectedColor: Color,
): Color {
    val color = if (selected) selectedColor else unselectedColor
    val opacity = when {
        pressed && selected -> top.yukonga.miuix.kmp.basic.NavigationBarDefaults.SelectedPressedAlpha
        pressed -> top.yukonga.miuix.kmp.basic.NavigationBarDefaults.UnselectedPressedAlpha
        selected -> 1f
        else -> top.yukonga.miuix.kmp.basic.NavigationBarDefaults.UnselectedAlpha
    }
    return color.copy(alpha = color.alpha * opacity)
}
