package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import com.takekazex.hypertweak.ui.liquid.IosLiquidGlassNavigationBar
import com.takekazex.hypertweak.ui.effect.rememberContentReady
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
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
    onNavigateToAbout: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val contentReady = rememberContentReady()
    val isDark = isSystemInDarkTheme()
    val blurActive = true
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val floatingBarColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
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
                        isBlurActive = blurActive
                    )
                } else {
                    FloatingNavigationBar(
                        modifier = if (blurActive) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = floatingBarShape,
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f)),
                                    ),
                                ),
                                highlight = floatingHighlight,
                            )
                        } else {
                            Modifier
                        },
                        color = floatingBarColor,
                    ) {
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == 0,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.scrollToPage(0)
                                }
                            },
                            icon = Icons.Rounded.Home,
                            label = "Home"
                        )
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == 1,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.scrollToPage(1)
                                }
                            },
                            icon = Icons.Rounded.Extension,
                            label = "Tweaks"
                        )
                        FloatingNavigationBarItem(
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
                        .then(
                            if (blurActive) {
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = RectangleShape,
                                    blurRadius = 25f,
                                    colors = BlurDefaults.blurColors(
                                        blendColors = listOf(
                                            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
                                        ),
                                    ),
                                )
                            } else {
                                Modifier
                            }
                        ),
                    color = barColor
                ) {
                    NavigationBarItem(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.scrollToPage(0)
                            }
                        },
                        icon = Icons.Rounded.Home,
                        label = "Home"
                    )
                    NavigationBarItem(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.scrollToPage(1)
                            }
                        },
                        icon = Icons.Rounded.Extension,
                        label = "Tweaks"
                    )
                    NavigationBarItem(
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
                                packageName = "com.takekazex.hypertweak",
                                targetSdk = 37,
                                backdrop = backdrop
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
                                backdrop = backdrop
                            )
                        }
                    }
                }
            }
        }
    }
}
