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
import top.yukonga.miuix.kmp.basic.NavigationBar
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
import androidx.compose.ui.Alignment
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
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    hideFingerprint: Boolean,
    onHideFingerprintChange: (Boolean) -> Unit,
    sliderShowPercentage: Boolean,
    onSliderShowPercentageChange: (Boolean) -> Unit,
    sliderSamePercentageStyle: Boolean,
    onSliderSamePercentageChange: (Boolean) -> Unit,
    showInSettings: Boolean,
    onShowInSettingsChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    hideGestureBar: Boolean,
    onHideGestureBarChange: (Boolean) -> Unit,
    gestureBarRaiseLayout: Boolean,
    onGestureBarRaiseLayoutChange: (Boolean) -> Unit,
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
    unlockPasskey: Boolean,
    onUnlockPasskeyChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onNavigateToHiddenFeatures: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
    onHotReload: () -> Unit,
    onRestartScope: (systemUi: Boolean, settings: Boolean, aod: Boolean, securityCenter: Boolean, scanner: Boolean, milink: Boolean, bluetooth: Boolean) -> Unit,
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
                        top.yukonga.miuix.kmp.basic.NavigationItem("Home", Icons.Rounded.Home),
                        top.yukonga.miuix.kmp.basic.NavigationItem("Tweaks", Icons.Rounded.Extension),
                        top.yukonga.miuix.kmp.basic.NavigationItem("Settings", Icons.Rounded.Settings)
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
                            label = "Home"
                        )
                        MyFloatingNavigationBarItem(
                            selected = pagerState.currentPage == 1,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.scrollToPage(1)
                                }
                            },
                            icon = Icons.Rounded.Extension,
                            label = "Tweaks"
                        )
                        MyFloatingNavigationBarItem(
                            selected = pagerState.currentPage == 2,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.scrollToPage(2)
                                }
                            },
                            icon = Icons.Rounded.Settings,
                            label = "Settings"
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
                        label = "Home"
                    )
                    MyNavigationBarItem(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.scrollToPage(1)
                            }
                        },
                        icon = Icons.Rounded.Extension,
                        label = "Tweaks"
                    )
                    MyNavigationBarItem(
                        selected = pagerState.currentPage == 2,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.scrollToPage(2)
                            }
                        },
                        icon = Icons.Rounded.Settings,
                        label = "Settings"
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
                                packageName = "com.takekazex.hypertweak",
                                targetSdk = 37,
                                backdrop = backdrop,
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
                                hideFingerprint = hideFingerprint,
                                onHideFingerprintChange = onHideFingerprintChange,
                                sliderShowPercentage = sliderShowPercentage,
                                onSliderShowPercentageChange = onSliderShowPercentageChange,
                                sliderSamePercentageStyle = sliderSamePercentageStyle,
                                onSliderSamePercentageChange = onSliderSamePercentageChange,
                                hideGestureBar = hideGestureBar,
                                onHideGestureBarChange = onHideGestureBarChange,
                                gestureBarRaiseLayout = gestureBarRaiseLayout,
                                onGestureBarRaiseLayoutChange = onGestureBarRaiseLayoutChange,
                                unlockPasskey = unlockPasskey,
                                onUnlockPasskeyChange = onUnlockPasskeyChange,
                                disableSpatialAudio = disableSpatialAudio,
                                onDisableSpatialAudioChange = onDisableSpatialAudioChange,
                                forceAdaptiveAnc = forceAdaptiveAnc,
                                onForceAdaptiveAncChange = onForceAdaptiveAncChange,
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
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                                useMonet = useMonet,
                                onUseMonetChange = onUseMonetChange,
                                seedColorHex = seedColorHex,
                                onSeedColorChange = onSeedColorChange,
                                useFloatingBottomBar = useFloatingBottomBar,
                                onUseFloatingBottomBarChange = onUseFloatingBottomBarChange,
                                floatingBarStyle = floatingBarStyle,
                                onFloatingBarStyleChange = onFloatingBarStyleChange,
                                predictiveBackStyle = predictiveBackStyle,
                                onPredictiveBackStyleChange = onPredictiveBackStyleChange,
                                predictiveBackFollowGesture = predictiveBackFollowGesture,
                                onPredictiveBackFollowGestureChange = onPredictiveBackFollowGestureChange,
                                allowLandscape = allowLandscape,
                                onAllowLandscapeChange = onAllowLandscapeChange,
                                pageScale = pageScale,
                                onPageScaleChange = onPageScaleChange,
                                onNavigateToAbout = onNavigateToAbout,
                                onNavigateToDebugLogs = onNavigateToDebugLogs,
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

    // Adjust size based on label for optical balance
    val customIconSize = when (label) {
        "Home" -> 30.dp      // Visually smallest, scale up from 26.dp
        "Tweaks" -> 26.0.dp  // Normal/average, original defaults to 26.dp
        "Settings" -> 24.5.dp // Solid icon, scale down slightly from 26.dp
        else -> top.yukonga.miuix.kmp.basic.NavigationBarDefaults.IconSize
    }

    Column(
        modifier = modifier
            .height(itemHeight)
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

            top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode.TextOnly -> {
                top.yukonga.miuix.kmp.basic.Text(
                    modifier = Modifier
                        .padding(vertical = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.BottomPadding),
                    text = label,
                    color = tint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = top.yukonga.miuix.kmp.basic.NavigationBarDefaults.TextFontSize,
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

    // Adjust size based on label for optical balance
    val customIconSize = when (label) {
        "Home" -> 32.dp      // Visually smallest, scale up from 28.dp
        "Tweaks" -> 28.0.dp  // Normal/average, original defaults to 28.dp
        "Settings" -> 26.5.dp // Solid icon, scale down slightly from 28.dp
        else -> top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults.IconSize
    }

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
