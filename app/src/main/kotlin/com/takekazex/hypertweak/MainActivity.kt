package com.takekazex.hypertweak

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.compose.NavigationEventState
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.MainPagerScreen
import com.takekazex.hypertweak.ui.page.AboutPage
import com.takekazex.hypertweak.ui.page.CreditsPage
import com.takekazex.hypertweak.ui.effect.scalePredictiveBackDecorator

internal fun getSystemAccentColor(context: Context): Int {
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

        setContent {
            // Theme settings states
            var themeMode by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_THEME_MODE, 0)) }
            var useMonet by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_USE_MONET, false)) }
            var seedColorHex by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_SEED_COLOR, 0xFF007AFF.toInt())) }
            var useFloatingBottomBar by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, false)) }
            var floatingBarStyle by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_FLOATING_BAR_STYLE, 0)) }
            var predictiveBackStyle by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, 1)) }
            var predictiveBackFollowGesture by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true)) }
            var allowLandscape by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_ALLOW_LANDSCAPE, false)) }

            val serviceConnected by XposedServiceManager.serviceFlow.collectAsState()

            // State variables for toggles
            var aodFullscreen by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)) }
            var removeGms by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) }
            var hideFingerprint by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)) }
            var showInSettings by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)) }
            var hideLauncherIcon by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)) }
            var sliderShowPercentage by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) }
            var sliderSamePercentageStyle by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)) }

            val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(allowLandscape) {
                requestedOrientation = if (allowLandscape) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
                    android.util.Log.d("HyperTweak", "First back completed. Pager scrolling to 0.")
                    coroutineScope.launch {
                        pagerState.scrollToPage(0)
                    }
                }
            )

            // Scale predictive back states
            var exitingPageKey by remember { mutableStateOf<String?>(null) }
            val exitAnimatable = remember { Animatable(0f) }

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
                                    allowLandscape = allowLandscape,
                                    onAllowLandscapeChange = { allowed ->
                                        allowLandscape = allowed
                                        coroutineScope.launch(Dispatchers.IO) {
                                            Preferences.putBoolean(Preferences.KEY_ALLOW_LANDSCAPE, allowed)
                                        }
                                    },
                                    onNavigateToAbout = {
                                        backStack.add(Route.About)
                                    }
                                )
                            }
                            entry<Route.About> {
                                AboutPage(
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
                            val decoratedModifier = if (predictiveBackStyle == 2) {
                                Modifier.scalePredictiveBackDecorator(
                                    transitionState = gestureState?.transitionState,
                                    contentPageKey = content.contentKey,
                                    currentPageKey = backStack.lastOrNull(),
                                    exitFollowGesture = predictiveBackFollowGesture,
                                    exitAnimatableValue = exitAnimatable.value,
                                    exitingPageKey = exitingPageKey
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

                    val onBack: () -> Unit = {
                        val isPredictiveInProgress = gestureState?.transitionState is NavigationEventTransitionState.InProgress
                        if (predictiveBackStyle == 2 && isPredictiveInProgress) {
                            coroutineScope.launch {
                                val currentProgress = (gestureState?.transitionState as? NavigationEventTransitionState.InProgress)
                                    ?.latestEvent?.progress ?: 0f
                                exitingPageKey = backStack.lastOrNull()?.toString()
                                exitAnimatable.snapTo(currentProgress)
                                exitAnimatable.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = 200,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                exitAnimatable.snapTo(0f)
                                if (backStack.size > 1) {
                                    backStack.removeLast()
                                }
                            }
                        } else {
                            if (backStack.size > 1) {
                                backStack.removeLast()
                            }
                        }
                    }

                    val sceneState = rememberSceneState(
                        entries = entries,
                        sceneStrategies = listOf(SinglePaneSceneStrategy()),
                        sceneDecoratorStrategies = emptyList(),
                        sharedTransitionScope = null,
                        onBack = onBack
                    )

                    val currentInfo = SceneInfo(sceneState.currentScene)
                    val previousSceneInfos = sceneState.previousScenes.map { SceneInfo(it) }
                    gestureState = rememberNavigationEventState(
                        currentInfo = currentInfo,
                        backInfo = previousSceneInfos
                    )

                    // Standard BackHandler to definitively intercept back on sub-pages
                    BackHandler(enabled = backStack.size > 1 && predictiveBackStyle == 0) {
                        android.util.Log.d("HyperTweak", "BackHandler fired. backStack size = ${backStack.size}")
                        onBack()
                    }

                    NavigationBackHandler(
                        state = gestureState!!,
                        isBackEnabled = backStack.size > 1 && predictiveBackStyle != 0,
                        onBackCompleted = {
                            android.util.Log.d("HyperTweak", "Second NavigationBackHandler completed. backStack size = ${backStack.size}")
                            onBack()
                        }
                    )

                    NavDisplay(
                        sceneState = sceneState,
                        navigationEventState = gestureState!!,
                        transitionSpec = {
                            if (predictiveBackStyle == 2) {
                                ContentTransform(
                                    targetContentEnter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                                    initialContentExit = slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut(),
                                    sizeTransform = null
                                )
                            } else {
                                defaultTransitionSpec<Route>().invoke(this)
                            }
                        },
                        predictivePopTransitionSpec = { swipeEdge ->
                            if (predictiveBackStyle == 2 || predictiveBackStyle == 0) {
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
                            if (predictiveBackStyle == 2) {
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
