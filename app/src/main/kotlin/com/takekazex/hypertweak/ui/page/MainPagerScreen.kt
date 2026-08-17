package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import kotlinx.coroutines.launch
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
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainPagerScreen(
    pagerState: PagerState,
    useFloatingBottomBar: Boolean,
    floatingBarStyle: Int,
    backdrop: LayerBackdrop,
    moduleActive: Boolean,
    hotReloadAvailable: Boolean,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    pendingRestartScopes: RestartScopeSelection,
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    quickShareEnabled: Boolean,
    onQuickShareEnabledChange: (Boolean) -> Unit,
    hideFingerprint: Boolean,
    onHideFingerprintChange: (Boolean) -> Unit,
    hideLockscreenStatusBar: Boolean,
    onHideLockscreenStatusBarChange: (Boolean) -> Unit,
    lockscreenFingerprintAvoid: Int,
    onLockscreenFingerprintAvoidChange: (Int) -> Unit,
    onNavigateToChargingDetail: () -> Unit,
    sliderShowPercentage: Boolean,
    onSliderShowPercentageChange: (Boolean) -> Unit,
    sliderSamePercentageStyle: Boolean,
    onSliderSamePercentageChange: (Boolean) -> Unit,
    showInSettings: Boolean,
    onShowInSettingsChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    immediateMonetRefresh: Boolean,
    onImmediateMonetRefreshChange: (Boolean) -> Unit,
    hideGestureBar: Boolean,
    onHideGestureBarChange: (Boolean) -> Unit,
    gestureBarRaiseLayout: Boolean,
    onGestureBarRaiseLayoutChange: (Boolean) -> Unit,
    gestureBarActionsEnabled: Boolean,
    onGestureBarActionsEnabledChange: (Boolean) -> Unit,
    powerButtonCts: Boolean,
    onPowerButtonCtsChange: (Boolean) -> Unit,
    gestureBarLongPressAction: Int,
    onGestureBarLongPressActionChange: (Int) -> Unit,
    gestureBarDoubleTapAction: Int,
    onGestureBarDoubleTapActionChange: (Int) -> Unit,
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
    miuiBackGestureHook: Boolean,
    onMiuiBackGestureHookChange: (Boolean) -> Unit,
    crossTaskWallpaperBackground: Boolean,
    onCrossTaskWallpaperBackgroundChange: (Boolean) -> Unit,
    aospBackIndicator: Boolean,
    onAospBackIndicatorChange: (Boolean) -> Unit,
    aospBackHaptics: Boolean,
    onAospBackHapticsChange: (Boolean) -> Unit,
    aospBackHapticsEnhanced: Boolean,
    onAospBackHapticsEnhancedChange: (Boolean) -> Unit,
    aospBackSlideAnimation: Boolean,
    onAospBackSlideAnimationChange: (Boolean) -> Unit,
    launcherMajor: Int,
    launcherSupportsBackRoute: Boolean,
    aospBackMiuiHomeHooks: Boolean,
    onAospBackMiuiHomeHooksChange: (Boolean) -> Unit,
    predictiveBackFollowGesture: Boolean,
    onPredictiveBackFollowGestureChange: (Boolean) -> Unit,
    allowLandscape: Boolean,
    onAllowLandscapeChange: (Boolean) -> Unit,
    pageScale: Float,
    onPageScaleChange: (Float) -> Unit,
    unlockPasskey: Boolean,
    onUnlockPasskeyChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,
    fcmLiveEnabled: Boolean,
    onFcmLiveEnabledChange: (Boolean) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onNavigateToHiddenFeatures: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
    onNavigateToPredictiveBackApps: () -> Unit,
    onNavigateToAospRestore: () -> Unit,
    onNavigateToIconTuner: () -> Unit,
    onNavigateToGlassTuner: () -> Unit,
    onNavigateToWatermark: () -> Unit,
    onClearAllSettings: () -> Unit,
    onHotReload: (restartAllScopes: Boolean) -> Unit,
    onRestartScope: (RestartScopeSelection) -> Unit,
    appLanguage: Int,
    onAppLanguageChange: (Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val contentReady = rememberContentReady()
    val isDark = isSystemInDarkTheme()
    val floatingBarShape = RoundedCornerShape(top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults.CornerRadius)
    val floatingHighlight = remember(isDark) {
        if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    }

    Scaffold(
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
                        pagerState = pagerState,
                        onItemClick = { index ->
                            coroutineScope.launch {
                                pagerState.scrollToPage(index)
                            }
                        },
                        backdrop = backdrop,
                        isBlurActive = true
                    )
                } else {
                    FloatingNavigationBar(
                        modifier = Modifier.textureBlur(
                            backdrop = backdrop,
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
                            selected = pagerState.currentPage == 0,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.scrollToPage(0)
                                }
                            },
                            icon = Icons.Rounded.Home,
                            label = stringResource(R.string.main_tab_home),
                            iconSize = 32.dp
                        )
                        MyFloatingNavigationBarItem(
                            selected = pagerState.currentPage == 1,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.scrollToPage(1)
                                }
                            },
                            icon = Icons.Rounded.Extension,
                            label = stringResource(R.string.main_tab_tweaks),
                            iconSize = 28.0.dp
                        )
                        MyFloatingNavigationBarItem(
                            selected = pagerState.currentPage == 2,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.scrollToPage(2)
                                }
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
                        .textureBlur(
                            backdrop = backdrop,
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
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.scrollToPage(0)
                            }
                        },
                        icon = Icons.Rounded.Home,
                        label = stringResource(R.string.main_tab_home),
                        iconSize = 30.dp
                    )
                    MyNavigationBarItem(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.scrollToPage(1)
                            }
                        },
                        icon = Icons.Rounded.Extension,
                        label = stringResource(R.string.main_tab_tweaks),
                        iconSize = 26.0.dp
                    )
                    MyNavigationBarItem(
                        selected = pagerState.currentPage == 2,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.scrollToPage(2)
                            }
                        },
                        icon = Icons.Rounded.Settings,
                        label = stringResource(R.string.main_tab_settings),
                        iconSize = 24.5.dp
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
                beyondViewportPageCount = 2
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
                                onHotReload = onHotReload,
                                onRestartScope = onRestartScope
                            )
                        }
                    }
                    1 -> {
                        if (isCurrent || contentReady) {
                            TweaksScreenContent(
                                padding = padding,
                                aodFullscreen = aodFullscreen,
                                onAodFullscreenChange = onAodFullscreenChange,
                                removeGms = removeGms,
                                onRemoveGmsChange = onRemoveGmsChange,
                                quickShareEnabled = quickShareEnabled,
                                onQuickShareEnabledChange = onQuickShareEnabledChange,
                                hideFingerprint = hideFingerprint,
                                onHideFingerprintChange = onHideFingerprintChange,
                                hideLockscreenStatusBar = hideLockscreenStatusBar,
                                onHideLockscreenStatusBarChange = onHideLockscreenStatusBarChange,
                                sliderShowPercentage = sliderShowPercentage,
                                onSliderShowPercentageChange = onSliderShowPercentageChange,
                                sliderSamePercentageStyle = sliderSamePercentageStyle,
                                onSliderSamePercentageChange = onSliderSamePercentageChange,
                                hideGestureBar = hideGestureBar,
                                onHideGestureBarChange = onHideGestureBarChange,
                                gestureBarRaiseLayout = gestureBarRaiseLayout,
                                onGestureBarRaiseLayoutChange = onGestureBarRaiseLayoutChange,
                                gestureBarActionsEnabled = gestureBarActionsEnabled,
                                onGestureBarActionsEnabledChange = onGestureBarActionsEnabledChange,
                                powerButtonCts = powerButtonCts,
                                onPowerButtonCtsChange = onPowerButtonCtsChange,
                                gestureBarLongPressAction = gestureBarLongPressAction,
                                onGestureBarLongPressActionChange = onGestureBarLongPressActionChange,
                                gestureBarDoubleTapAction = gestureBarDoubleTapAction,
                                onGestureBarDoubleTapActionChange = onGestureBarDoubleTapActionChange,
                                miuiBackGestureHook = miuiBackGestureHook,
                                onMiuiBackGestureHookChange = onMiuiBackGestureHookChange,
                                crossTaskWallpaperBackground = crossTaskWallpaperBackground,
                                onCrossTaskWallpaperBackgroundChange = onCrossTaskWallpaperBackgroundChange,
                                aospBackIndicator = aospBackIndicator,
                                onAospBackIndicatorChange = onAospBackIndicatorChange,
                                aospBackHaptics = aospBackHaptics,
                                onAospBackHapticsChange = onAospBackHapticsChange,
                                aospBackHapticsEnhanced = aospBackHapticsEnhanced,
                                onAospBackHapticsEnhancedChange = onAospBackHapticsEnhancedChange,
                                aospBackSlideAnimation = aospBackSlideAnimation,
                                onAospBackSlideAnimationChange = onAospBackSlideAnimationChange,
                                launcherSupportsBackRoute = launcherSupportsBackRoute,
                                unlockPasskey = unlockPasskey,
                                onUnlockPasskeyChange = onUnlockPasskeyChange,
                                disableSpatialAudio = disableSpatialAudio,
                                onDisableSpatialAudioChange = onDisableSpatialAudioChange,
                                forceAdaptiveAnc = forceAdaptiveAnc,
                                onForceAdaptiveAncChange = onForceAdaptiveAncChange,
                                fcmLiveEnabled = fcmLiveEnabled,
                                onFcmLiveEnabledChange = onFcmLiveEnabledChange,
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
                                immediateMonetRefresh = immediateMonetRefresh,
                                onImmediateMonetRefreshChange = onImmediateMonetRefreshChange,
                                lockscreenFingerprintAvoid = lockscreenFingerprintAvoid,
                                onLockscreenFingerprintAvoidChange = onLockscreenFingerprintAvoidChange,
                                onNavigateToChargingDetail = onNavigateToChargingDetail,
                                launcherMajor = launcherMajor,
                                launcherSupportsBackRoute = launcherSupportsBackRoute,
                                aospBackMiuiHomeHooks = aospBackMiuiHomeHooks,
                                onAospBackMiuiHomeHooksChange = onAospBackMiuiHomeHooksChange,
                                onNavigateToPredictiveBackApps = onNavigateToPredictiveBackApps,
                                onNavigateToAospRestore = onNavigateToAospRestore,
                                onNavigateToIconTuner = onNavigateToIconTuner,
                                onNavigateToGlassTuner = onNavigateToGlassTuner,
                                onNavigateToWatermark = onNavigateToWatermark,
                                themeSummary = listOf(
                                    stringResource(R.string.main_theme_follow_system),
                                    stringResource(R.string.main_theme_light),
                                    stringResource(R.string.main_theme_dark)
                                ).getOrElse(themeMode) { stringResource(R.string.main_theme_follow_system) },
                                onNavigateToAppearance = onNavigateToAppearance,
                                allowLandscape = allowLandscape,
                                onAllowLandscapeChange = onAllowLandscapeChange,
                                onNavigateToAbout = onNavigateToAbout,
                                onNavigateToDebugLogs = onNavigateToDebugLogs,
                                onNavigateToAppShortcuts = onNavigateToAppShortcuts,
                                onClearAllSettings = onClearAllSettings,
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
) {
    val itemHeight = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.ItemHeight
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> if (selected) {
            onSurfaceContainerColor.copy(alpha = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.SelectedPressedAlpha)
        } else {
            onSurfaceContainerColor.copy(alpha = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.UnselectedPressedAlpha)
        }

        selected -> onSurfaceContainerColor

        else -> onSurfaceContainerColor.copy(top.yukonga.miuix.kmp.basic.NavigationBarDefaults.UnselectedAlpha)
    }
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
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> if (selected) {
            onSurfaceContainerColor.copy(alpha = top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults.SelectedPressedAlpha)
        } else {
            onSurfaceContainerColor.copy(alpha = top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults.UnselectedPressedAlpha)
        }

        selected -> onSurfaceContainerColor

        else -> onSurfaceContainerColor.copy(top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults.UnselectedAlpha)
    }

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
