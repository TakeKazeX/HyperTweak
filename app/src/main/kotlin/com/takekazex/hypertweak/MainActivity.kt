package com.takekazex.hypertweak

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner

import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.os.Build

import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.basic.Check

import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import com.takekazex.hypertweak.ui.effect.BgEffectBackground
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.SceneState
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.animateFloat
import top.yukonga.miuix.kmp.basic.NavigationItem
import com.takekazex.hypertweak.ui.liquid.IosLiquidGlassNavigationBar
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigationevent.compose.NavigationEventState
import top.yukonga.miuix.kmp.basic.CardDefaults
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.NavigationEvent
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.compose.ui.draw.drawWithContent

enum class Screen {
    HOME,
    TWEAKS,
    SETTINGS
}

fun getSystemAccentColor(context: Context): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            context.getColor(android.R.color.system_accent1_500)
        } catch (e: Throwable) {
            0xFF007AFF.toInt()
        }
    } else {
        0xFF007AFF.toInt()
    }
}

class MainActivity : ComponentActivity() {

    // Intercepted by ModuleStatusHooker. Keep annotation prevents R8 optimization/inlining.
    @Keep
    fun isModuleActive(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // Init connection to LSPosed preferences
        XposedServiceManager.init()

        setContent {
            val dispatcherOwner = rememberNavigationEventDispatcherOwner(parent = null)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides dispatcherOwner
            ) {
                // Theme settings states
                var themeMode by remember { mutableStateOf(0) } // 0 = System, 1 = Light, 2 = Dark
                var useMonet by remember { mutableStateOf(false) }
                var seedColorHex by remember { mutableStateOf(0xFF007AFF.toInt()) }
                var useFloatingBottomBar by remember { mutableStateOf(false) }
                var floatingBarStyle by remember { mutableStateOf(0) } // 0 = Miuix, 1 = iOS-like
                var predictiveBackStyle by remember { mutableStateOf(0) } // 0 = Miuix, 1 = Scale
                var predictiveBackFollowGesture by remember { mutableStateOf(true) }

                val serviceConnected by XposedServiceManager.serviceFlow.collectAsState()

                // State variables for toggles
                var aodFullscreen by remember { mutableStateOf(false) }
                var removeGms by remember { mutableStateOf(false) }
                var hideFingerprint by remember { mutableStateOf(false) }
                var showInSettings by remember { mutableStateOf(false) }
                var hideLauncherIcon by remember { mutableStateOf(false) }
                var sliderShowPercentage by remember { mutableStateOf(false) }
                var sliderSamePercentageStyle by remember { mutableStateOf(false) }

                val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
                val coroutineScope = rememberCoroutineScope()

                // Sync UI state when Preferences are initialized or service binds (All reads asynchronous)
                LaunchedEffect(serviceConnected) {
                    if (Preferences.isInitialized) {
                        coroutineScope.launch(Dispatchers.IO) {
                            val aod = Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)
                            val removeGmsVal = Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)
                            val hideFingerprintVal = Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)
                            val showInSettingsVal = Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)
                            val hideLauncherIconVal = Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)
                            val sliderShowVal = Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)
                            val sliderSameStyleVal = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)

                            val themeModeVal = Preferences.getInt(Preferences.KEY_THEME_MODE, 0)
                            val useMonetVal = Preferences.getBoolean(Preferences.KEY_USE_MONET, false)
                            val seedColorHexVal = Preferences.getInt(Preferences.KEY_SEED_COLOR, 0xFF007AFF.toInt())
                            val useFloatingBar = Preferences.getBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, false)
                            val floatingBarStyleVal = Preferences.getInt(Preferences.KEY_FLOATING_BAR_STYLE, 0)
                            val predictiveStyleVal = Preferences.getInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, 0)
                            val predictiveFollowVal = Preferences.getBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true)

                            withContext(Dispatchers.Main) {
                                aodFullscreen = aod
                                removeGms = removeGmsVal
                                hideFingerprint = hideFingerprintVal
                                showInSettings = showInSettingsVal
                                hideLauncherIcon = hideLauncherIconVal
                                sliderShowPercentage = sliderShowVal
                                sliderSamePercentageStyle = sliderSameStyleVal

                                themeMode = themeModeVal
                                useMonet = useMonetVal
                                seedColorHex = seedColorHexVal
                                useFloatingBottomBar = useFloatingBar
                                floatingBarStyle = floatingBarStyleVal
                                predictiveBackStyle = predictiveStyleVal
                                predictiveBackFollowGesture = predictiveFollowVal
                            }
                        }
                    }
                }

                val context = androidx.compose.ui.platform.LocalContext.current
                val moduleActive = isModuleActive() || serviceConnected != null
                val isDark = isSystemInDarkTheme()
                val resolvedSeedColorHex = remember(seedColorHex, context) {
                    if (seedColorHex == 0) {
                        getSystemAccentColor(context)
                    } else {
                        seedColorHex
                    }
                }

                val controller = remember(themeMode, useMonet, resolvedSeedColorHex, isDark) {
                    val mode = when (themeMode) {
                        1 -> if (useMonet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
                        2 -> if (useMonet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
                        else -> if (useMonet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
                    }
                    ThemeController(
                        colorSchemeMode = mode,
                        keyColor = Color(resolvedSeedColorHex),
                        isDark = when (themeMode) {
                            1 -> false
                            2 -> true
                            else -> null
                        }
                    )
                }

                val backStack = remember { mutableStateListOf<Route>(Route.Main) }

                val isPagerBackHandlerEnabled by remember(backStack, pagerState.currentPage) {
                    derivedStateOf {
                        backStack.lastOrNull() is Route.Main && backStack.size == 1 && pagerState.currentPage != 0
                    }
                }

                val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

                NavigationBackHandler(
                    state = navEventState,
                    isBackEnabled = isPagerBackHandlerEnabled,
                    onBackCompleted = {
                        coroutineScope.launch {
                            pagerState.scrollToPage(0)
                        }
                    }
                )

                // Scale predictive back states
                var exitingPageKey by remember { mutableStateOf<String?>(null) }
                val exitAnimatable = remember { Animatable(0f) }
                var inPredictiveBackAnimation by remember { mutableStateOf(false) }

                MiuixTheme(controller = controller) {
                    val surfaceColor = MiuixTheme.colorScheme.surface
                    val backdrop = rememberLayerBackdrop {
                        drawRect(surfaceColor)
                        drawContent()
                    }

                    var gestureState: NavigationEventState<SceneInfo<Route>>? = null

                    val entryProvider = remember(backStack) {
                        entryProvider<Route> {
                            entry<Route.Main> {
                                MainPagerScreen(
                                    pagerState = pagerState,
                                    useFloatingBottomBar = useFloatingBottomBar,
                                    floatingBarStyle = floatingBarStyle,
                                    backdrop = backdrop,
                                    moduleActive = moduleActive,
                                    aodFullscreen = aodFullscreen,
                                    onAodFullscreenChange = { checked ->
                                        aodFullscreen = checked
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_AOD_FULLSCREEN, checked)
                                        }
                                    },
                                    removeGms = removeGms,
                                    onRemoveGmsChange = { checked ->
                                        removeGms = checked
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, checked)
                                        }
                                    },
                                    hideFingerprint = hideFingerprint,
                                    onHideFingerprintChange = { checked ->
                                        hideFingerprint = checked
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT, checked)
                                        }
                                    },
                                    sliderShowPercentage = sliderShowPercentage,
                                    onSliderShowPercentageChange = { checked ->
                                        sliderShowPercentage = checked
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                                        }
                                    },
                                    sliderSamePercentageStyle = sliderSamePercentageStyle,
                                    onSliderSamePercentageChange = { checked ->
                                        sliderSamePercentageStyle = checked
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                                        }
                                    },
                                    showInSettings = showInSettings,
                                    onShowInSettingsChange = { checked ->
                                        showInSettings = checked
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_SHOW_IN_SETTINGS, checked)
                                        }
                                    },
                                    hideLauncherIcon = hideLauncherIcon,
                                    onHideLauncherIconChange = { checked ->
                                        hideLauncherIcon = checked
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, checked)
                                            setLauncherIconVisible(this@MainActivity, !checked)
                                        }
                                    },
                                    themeMode = themeMode,
                                    onThemeModeChange = { mode ->
                                        themeMode = mode
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putInt(Preferences.KEY_THEME_MODE, mode)
                                        }
                                    },
                                    useMonet = useMonet,
                                    onUseMonetChange = { monet ->
                                        useMonet = monet
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_USE_MONET, monet)
                                        }
                                    },
                                    seedColorHex = seedColorHex,
                                    onSeedColorChange = { color ->
                                        seedColorHex = color
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putInt(Preferences.KEY_SEED_COLOR, color)
                                        }
                                    },
                                    onUseFloatingBottomBarChange = { floating ->
                                        useFloatingBottomBar = floating
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, floating)
                                        }
                                    },
                                    onFloatingBarStyleChange = { style ->
                                        floatingBarStyle = style
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putInt(Preferences.KEY_FLOATING_BAR_STYLE, style)
                                        }
                                    },
                                    predictiveBackStyle = predictiveBackStyle,
                                    onPredictiveBackStyleChange = { style ->
                                        predictiveBackStyle = style
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, style)
                                        }
                                    },
                                    predictiveBackFollowGesture = predictiveBackFollowGesture,
                                    onPredictiveBackFollowGestureChange = { follow ->
                                        predictiveBackFollowGesture = follow
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, follow)
                                        }
                                    },
                                    onNavigateToAbout = {
                                        backStack.add(Route.About)
                                    }
                                )
                            }
                            entry<Route.About> {
                                AboutPage(
                                    backdrop = backdrop,
                                    onBack = {
                                        if (backStack.size > 1) backStack.removeLast()
                                    },
                                    onViewSourceCode = {
                                        try {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/takekazex/HyperTweak"))
                                            this@MainActivity.startActivity(intent)
                                        } catch (e: Exception) {
                                            // Ignore
                                        }
                                    },
                                    onNavigateToCredits = {
                                        backStack.add(Route.Credits)
                                    }
                                )
                            }
                            entry<Route.Credits> {
                                CreditsPage(
                                    onBack = {
                                        if (backStack.size > 1) backStack.removeLast()
                                    }
                                )
                            }
                        }
                    }

                    val entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator<Route>(),
                        NavEntryDecorator<Route>(
                            onPop = { key ->
                                if (exitingPageKey == key.toString()) {
                                    exitingPageKey = null
                                }
                            }
                        ) { content ->
                            val decoratedModifier = if (predictiveBackStyle == 1) {
                                Modifier.scalePredictiveBackDecorator(
                                    transitionState = gestureState?.transitionState,
                                    contentPageKey = content.contentKey,
                                    currentPageKey = backStack.lastOrNull(),
                                    exitFollowGesture = predictiveBackFollowGesture,
                                    exitAnimatableValue = exitAnimatable.value,
                                    exitingPageKey = exitingPageKey,
                                    onInPredictiveBackChanged = { inAnim ->
                                        inPredictiveBackAnimation = inAnim
                                    }
                                )
                            } else {
                                Modifier
                            }
                            Box(modifier = decoratedModifier) {
                                content.Content()
                            }
                        }
                    )

                    val entries = rememberDecoratedNavEntries(
                        backStack = backStack,
                        entryDecorators = entryDecorators,
                        entryProvider = entryProvider
                    )

                    val sceneState = rememberSceneState(
                        entries = entries,
                        sceneStrategies = listOf(SinglePaneSceneStrategy()),
                        sceneDecoratorStrategies = emptyList(),
                        sharedTransitionScope = null,
                        onBack = {
                            coroutineScope.launch {
                                if (predictiveBackStyle == 1 && inPredictiveBackAnimation) {
                                    exitingPageKey = backStack.lastOrNull()?.toString()
                                    exitAnimatable.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(
                                            durationMillis = 200,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                    exitAnimatable.snapTo(0f)
                                }
                                if (backStack.size > 1) {
                                    backStack.removeLast()
                                }
                            }
                        }
                    )

                    val currentInfo = SceneInfo(sceneState.currentScene)
                    val previousSceneInfos = sceneState.previousScenes.map { SceneInfo(it) }
                    gestureState = rememberNavigationEventState(
                        currentInfo = currentInfo,
                        backInfo = previousSceneInfos
                    )

                    NavigationBackHandler(
                        state = gestureState!!,
                        isBackEnabled = backStack.size > 1,
                        onBackCompleted = {
                            coroutineScope.launch {
                                if (predictiveBackStyle == 1 && inPredictiveBackAnimation) {
                                    exitingPageKey = backStack.lastOrNull()?.toString()
                                    exitAnimatable.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(
                                            durationMillis = 200,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                    exitAnimatable.snapTo(0f)
                                }
                                if (backStack.size > 1) {
                                    backStack.removeLast()
                                }
                            }
                        }
                    )

                    NavDisplay(
                        sceneState = sceneState,
                        navigationEventState = gestureState!!,
                        predictivePopTransitionSpec = { swipeEdge ->
                            if (predictiveBackStyle == 1) {
                                ContentTransform(
                                    targetContentEnter = EnterTransition.None,
                                    initialContentExit = ExitTransition.None,
                                    sizeTransform = null
                                )
                            } else {
                                defaultPredictivePopTransitionSpec<Route>().invoke(this, swipeEdge)
                            }
                        },
                        popTransitionSpec = {
                            if (predictiveBackStyle == 1) {
                                ContentTransform(
                                    targetContentEnter = slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn(),
                                    initialContentExit = scaleOut(targetScale = 0.9f) + fadeOut(),
                                    sizeTransform = null
                                )
                            } else {
                                defaultPopTransitionSpec<Route>().invoke(this)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun setLauncherIconVisible(context: Context, visible: Boolean) {
        try {
            val pm = context.packageManager
            val componentName = ComponentName(context, "com.takekazex.hypertweak.MainActivityAlias")
            val state = if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            // Ignore
        }
    }
}

@Composable
fun MainPagerScreen(
    pagerState: androidx.compose.foundation.pager.PagerState,
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
    onNavigateToAbout: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
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
                    val items = remember {
                        listOf(
                            NavigationItem("Home", MiuixIcons.HorizontalSplit),
                            NavigationItem("Tweaks", MiuixIcons.Favorites),
                            NavigationItem("Settings", MiuixIcons.Settings)
                        )
                    }
                    IosLiquidGlassNavigationBar(
                        items = items,
                        selectedIndex = pagerState.currentPage,
                        onItemClick = { index ->
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
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
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                            icon = MiuixIcons.HorizontalSplit,
                            label = "Home"
                        )
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == 1,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            },
                            icon = MiuixIcons.Favorites,
                            label = "Tweaks"
                        )
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == 2,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(2)
                                }
                            },
                            icon = MiuixIcons.Settings,
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
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        icon = MiuixIcons.HorizontalSplit,
                        label = "Home"
                    )
                    NavigationBarItem(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        icon = MiuixIcons.Favorites,
                        label = "Tweaks"
                    )
                    NavigationBarItem(
                        selected = pagerState.currentPage == 2,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(2)
                            }
                        },
                        icon = MiuixIcons.Settings,
                        label = "Settings"
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> {
                        HomeScreenContent(
                            padding = padding,
                            moduleActive = moduleActive,
                            packageName = "com.takekazex.hypertweak",
                            targetSdk = 37
                        )
                    }
                    1 -> {
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
                            onSliderSamePercentageChange = onSliderSamePercentageChange
                        )
                    }
                    2 -> {
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
                            onNavigateToAbout = onNavigateToAbout
                        )
                    }
                }
            }
        }
    }
}

sealed interface Route : NavKey {
    data object Main : Route
    data object About : Route
    data object Credits : Route
}

@Composable
fun HomeScreenContent(
    padding: PaddingValues,
    moduleActive: Boolean,
    packageName: String,
    targetSdk: Int
) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (moduleActive) {
        if (isDark) Color(0xFF1B5E20) else Color(0xFFE8F5E9)
    } else {
        if (isDark) Color(0xFFB71C1C) else Color(0xFFFFEBEE)
    }

    val contentColor = if (moduleActive) {
        if (isDark) Color(0xFFC8E6C9) else Color(0xFF1B5E20)
    } else {
        if (isDark) Color(0xFFFFCDD2) else Color(0xFFB71C1C)
    }

    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Ink Tweaks",
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Large Status Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(containerColor)
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .offset(x = 24.dp, y = 24.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                        imageVector = if (moduleActive) MiuixIcons.Basic.Check else MiuixIcons.Info,
                            tint = contentColor.copy(alpha = 0.12f),
                            modifier = Modifier.size(120.dp),
                            contentDescription = null
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = if (moduleActive) "Module is ACTIVE" else "Module is NOT ACTIVE",
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (moduleActive)
                                "Native libxposed module loaded successfully."
                            else
                                "Please enable the module in LSPosed manager, ensure 'Ink Tweaks' itself is checked in the scope, and reboot or restart SystemUI.",
                            color = contentColor.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Section Title
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Diagnostics Details",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }

            // Diagnostics Card using BasicComponent
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "Module Package",
                        summary = packageName
                    )
                    BasicComponent(
                        title = "Target SDK",
                        summary = targetSdk.toString()
                    )
                    BasicComponent(
                        title = "Device System",
                        summary = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
                    )
                }
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
fun TweaksScreenContent(
    padding: PaddingValues,
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    hideFingerprint: Boolean,
    onHideFingerprintChange: (Boolean) -> Unit,
    sliderShowPercentage: Boolean,
    onSliderShowPercentageChange: (Boolean) -> Unit,
    sliderSamePercentageStyle: Boolean,
    onSliderSamePercentageChange: (Boolean) -> Unit
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Features",
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Scope 1: Lockscreen & Display
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Lockscreen & Display",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = aodFullscreen,
                        onCheckedChange = onAodFullscreenChange,
                        title = "Always-On Display Fullscreen",
                        summary = "Unlock full screen background support for AOD"
                    )
                    SwitchPreference(
                        checked = hideFingerprint,
                        onCheckedChange = onHideFingerprintChange,
                        title = "Hide Lockscreen Fingerprint",
                        summary = "Completely remove the fingerprint sensor circle icon on lockscreen"
                    )
                }
            }

            // Scope 2: Control Center
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Control Center",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = sliderShowPercentage,
                        onCheckedChange = onSliderShowPercentageChange,
                        title = "Slider Show Percentage Value",
                        summary = "Show percentage values on the brightness and volume sliders"
                    )
                    SwitchPreference(
                        checked = sliderSamePercentageStyle && sliderShowPercentage,
                        onCheckedChange = onSliderSamePercentageChange,
                        title = "Unify Percentage Style",
                        summary = "Always keep the volume slider percentage text visible to match the brightness style",
                        enabled = sliderShowPercentage
                    )
                }
            }

            // Scope 3: System Core
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "System Core",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = removeGms,
                        onCheckedChange = onRemoveGmsChange,
                        title = "Bypass GMS China ROM Restrictions",
                        summary = "Remove Google Play Services installation restrictions on Chinese firmware"
                    )
                }
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
fun SettingsScreenContent(
    padding: PaddingValues,
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
    useFloatingBottomBar: Boolean,
    onUseFloatingBottomBarChange: (Boolean) -> Unit,
    floatingBarStyle: Int,
    onFloatingBarStyleChange: (Int) -> Unit,
    predictiveBackStyle: Int,
    onPredictiveBackStyleChange: (Int) -> Unit,
    predictiveBackFollowGesture: Boolean,
    onPredictiveBackFollowGestureChange: (Boolean) -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Settings",
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Section Title: Theme Settings
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Theme Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }

            // Theme Settings Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OverlayDropdownPreference(
                        title = "Theme Mode",
                        items = listOf("Follow System", "Light", "Dark"),
                        selectedIndex = themeMode,
                        onSelectedIndexChange = onThemeModeChange
                    )

                    SwitchPreference(
                        checked = useMonet,
                        onCheckedChange = onUseMonetChange,
                        title = "Use Monet Accent Color",
                        summary = "Enable dynamic colors based on selected accent color"
                    )

                    OverlayDropdownPreference(
                        title = "Floating Bottom Bar",
                        items = listOf("Disabled", "Enabled"),
                        selectedIndex = if (useFloatingBottomBar) 1 else 0,
                        onSelectedIndexChange = { index ->
                            onUseFloatingBottomBarChange(index == 1)
                        }
                    )

                    AnimatedVisibility(
                        visible = useFloatingBottomBar,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        OverlayDropdownPreference(
                            title = "Floating Bottom Bar Style",
                            items = listOf("Miuix", "iOS-like"),
                            selectedIndex = floatingBarStyle,
                            onSelectedIndexChange = onFloatingBarStyleChange
                        )
                    }

                    OverlayDropdownPreference(
                        title = "Predictive Back Style",
                        items = listOf("Miuix", "Scale"),
                        selectedIndex = predictiveBackStyle,
                        onSelectedIndexChange = onPredictiveBackStyleChange
                    )

                    AnimatedVisibility(
                        visible = predictiveBackStyle == 1,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SwitchPreference(
                            checked = predictiveBackFollowGesture,
                            onCheckedChange = onPredictiveBackFollowGestureChange,
                            title = "Follow Gesture Direction",
                            summary = "Adjust scale pivot and exit animation translation based on swipe edge"
                        )
                    }
                }
            }

            // Accent Color Selection Row
            AnimatedVisibility(
                visible = useMonet,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "Accent Color Selection",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val systemAccentColor = remember(context) { getSystemAccentColor(context) }
                            val colors = buildList {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(0) // Device color
                                add(0xFF007AFF.toInt()) // Blue
                                add(0xFF4CAF50.toInt()) // Green
                                add(0xFFFF9800.toInt()) // Orange
                                add(0xFFF44336.toInt()) // Red
                                add(0xFF9C27B0.toInt()) // Purple
                                add(0xFF3F51B5.toInt()) // Indigo
                            }

                            colors.forEach { colorVal ->
                                val isSelected = seedColorHex == colorVal
                                val displayColor = if (colorVal == 0) Color(systemAccentColor) else Color(colorVal)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(displayColor)
                                        .clickable {
                                            onSeedColorChange(colorVal)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = MiuixIcons.Basic.Check,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp),
                                            contentDescription = "Selected"
                                        )
                                    } else if (colorVal == 0) {
                                        Text(
                                            text = "D",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Settings Header
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Module Preferences",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }

            // Module Preferences Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SwitchPreference(
                        checked = showInSettings,
                        onCheckedChange = onShowInSettingsChange,
                        title = "Show Entry in System Settings",
                        summary = "Inject an entry point for Ink Tweaks in the system Settings app"
                    )

                    SwitchPreference(
                        checked = hideLauncherIcon,
                        onCheckedChange = onHideLauncherIconChange,
                        title = "Hide Desktop Icon",
                        summary = "Hide launcher icon (access module via LSPosed or system settings)"
                    )
                }
            }

            // Section Title: Other
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Other",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                ArrowPreference(
                    title = "About",
                    summary = "Ink Tweaks v1.0",
                    onClick = onNavigateToAbout
                )
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
fun AboutPage(
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
    onViewSourceCode: () -> Unit,
    onNavigateToCredits: () -> Unit
) {
    val scrollState = rememberScrollState()
    val scrollProgress = remember(scrollState.value) {
        (scrollState.value.toFloat() / 600f).coerceIn(0f, 1f)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            BgEffectBackground(
                dynamicBackground = true,
                modifier = Modifier.fillMaxSize(),
                alpha = { 1f - scrollProgress },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(80.dp))

                    // Large App Logo & Title
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Favorites,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                            contentDescription = null
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Ink Tweaks",
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "Version 1.0 (1)",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Card with View Source Code & Credits links
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .textureBlur(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f)),
                                    ),
                                )
                            ),
                        colors = CardDefaults.defaultColors(Color.Transparent, Color.Transparent)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = "View Source Code",
                                summary = "Check the GitHub repository",
                                onClick = onViewSourceCode
                            )
                            ArrowPreference(
                                title = "Credits & Acknowledgements",
                                summary = "View open source libraries and contributors",
                                onClick = onNavigateToCredits
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            // Floating Top-Left Back Button with Status Bar Padding
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopStart
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MiuixTheme.colorScheme.surface.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        tint = MiuixTheme.colorScheme.onSurface,
                        contentDescription = "Back"
                    )
                }
            }
        }
    }
}

@Composable
fun CreditsPage(
    onBack: () -> Unit
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Credits",
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasicComponent(
                        title = "libxposed",
                        summary = "Native Xposed API 101 framework"
                    )
                    BasicComponent(
                        title = "LSPosed",
                        summary = "Mainstream Xposed framework implementation"
                    )
                    BasicComponent(
                        title = "KavaRef",
                        summary = "HighCapable Kotlin reflection library"
                    )
                    BasicComponent(
                        title = "EzHookTool",
                        summary = "Kotlin Xposed helper library by lingqiqi5211"
                    )
                    BasicComponent(
                        title = "DexKit",
                        summary = "Powerful Dex analysis tool for finding hook points dynamically"
                    )
                    BasicComponent(
                        title = "HiddenApiBypass",
                        summary = "Bypass restrictions on non-SDK interfaces in Android 9+"
                    )
                    BasicComponent(
                        title = "Miuix UI (compose-miuix-ui)",
                        summary = "Modern MIUI/HyperOS style Compose components"
                    )
                    BasicComponent(
                        title = "InstallerX Revived",
                        summary = "Inspiration for theme configuration and UI/UX layout design"
                    )
                    BasicComponent(
                        title = "HyperOShape",
                        summary = "Base logic for fingerprint icon drawing bypass"
                    )
                    BasicComponent(
                        title = "XiaomiHelper / HyperCeiler",
                        summary = "References for Settings preference injection"
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun rememberDeviceCornerRadius(defaultRadius: androidx.compose.ui.unit.Dp = 16.dp): androidx.compose.ui.unit.Dp {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    return remember(view, density) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            if (insets != null) {
                val corner = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
                    ?: insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)
                    ?: insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
                    ?: insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)

                if (corner != null) {
                    with(density) {
                        return@remember corner.radius.toDp()
                    }
                }
            }
        }
        defaultRadius
    }
}

@Composable
fun Modifier.scalePredictiveBackDecorator(
    transitionState: NavigationEventTransitionState?,
    contentPageKey: Any,
    currentPageKey: NavKey?,
    exitFollowGesture: Boolean,
    exitAnimatableValue: Float,
    exitingPageKey: String?,
    onInPredictiveBackChanged: (Boolean) -> Unit
): Modifier {
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val navContent = LocalNavAnimatedContentScope.current

    val containerHeightPx = windowInfo.containerSize.height
    val containerWidthPx = windowInfo.containerSize.width.toFloat()
    val pageKey = contentPageKey.toString()
    val transition = navContent.transition
    val deviceCornerRadius = rememberDeviceCornerRadius()

    if (pageKey == currentPageKey.toString() || exitingPageKey == pageKey) {
        val animatedScale by transition.animateFloat(
            transitionSpec = { tween(300) },
            label = "PredictiveScale"
        ) { state ->
            when (state) {
                androidx.compose.animation.EnterExitState.PostExit -> 0.85f
                else -> 1f
            }
        }

        LaunchedEffect(animatedScale) {
            onInPredictiveBackChanged(animatedScale != 1f)
        }

        val progressInProgress = (transitionState as? NavigationEventTransitionState.InProgress)
        val edge = progressInProgress?.latestEvent?.swipeEdge ?: 0
        val touchY = progressInProgress?.latestEvent?.touchY

        val currentPivotY = if (touchY != null && containerHeightPx > 0) {
            (touchY / containerHeightPx).coerceIn(0.1f, 0.9f)
        } else 0.5f

        val currentPivotX = if (edge == androidx.navigationevent.NavigationEvent.EDGE_LEFT) 0.8f else 0.2f

        val directionMultiplier = if (exitFollowGesture) {
            if (edge == androidx.navigationevent.NavigationEvent.EDGE_LEFT) 1f else -1f
        } else {
            1f
        }

        val exitProgress = if (pageKey != currentPageKey.toString()) 1f else exitAnimatableValue
        val animatedTranslationX = containerWidthPx * exitProgress * directionMultiplier
        val needsClip = (animatedScale != 1f) || exitingPageKey != null

        return this
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                translationX = animatedTranslationX
                transformOrigin = TransformOrigin(currentPivotX, currentPivotY)
            }
            .clip(
                if (needsClip) RoundedCornerShape(deviceCornerRadius)
                else RectangleShape
            )
    } else {
        if (transitionState is NavigationEventTransitionState.InProgress) {
            val progress = if (exitingPageKey == null) 1f else exitAnimatableValue
            val dynamicAlpha = 0.5f * (1f - progress)

            return this
                .graphicsLayer()
                .drawWithContent {
                    drawContent()
                    drawRect(color = Color.Black.copy(alpha = dynamicAlpha))
                }
        }
        return this
    }
}
