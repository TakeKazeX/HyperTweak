package com.takekazex.hypertweak.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.compose.NavigationEventState
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.MainPagerScreen
import com.takekazex.hypertweak.ui.page.AboutPage
import com.takekazex.hypertweak.ui.page.CreditsPage
import com.takekazex.hypertweak.ui.page.HiddenFeaturesPage
import com.takekazex.hypertweak.ui.page.AppShortcutsPage
import com.takekazex.hypertweak.ui.page.DebugLogPage
import com.takekazex.hypertweak.ui.effect.scalePredictiveBackDecorator
import com.takekazex.hypertweak.ui.effect.PredictiveBackAnimState
import com.takekazex.hypertweak.hook.HotReloadReport
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import kotlinx.coroutines.launch

@Composable
fun HyperTweakNavContainer(
    // Theme & Navigation States
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
    allowLandscape: Boolean,
    onAllowLandscapeChange: (Boolean) -> Unit,

    // Module Settings States
    moduleActive: Boolean,
    hotReloadAvailable: Boolean,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
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
    unlockPasskey: Boolean,
    onUnlockPasskeyChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,

    // Backdrop
    backdrop: LayerBackdrop,

    // Scaling
    pageScale: Float,
    onPageScaleChange: (Float) -> Unit,

    // Actions
    onViewSourceCode: () -> Unit,
    onHotReload: (restartAllScopes: Boolean) -> Unit,
    onRestartScope: (systemUi: Boolean, settings: Boolean, aod: Boolean, securityCenter: Boolean, scanner: Boolean, milink: Boolean, bluetooth: Boolean) -> Unit,
    onShortcutsChanged: () -> Unit,
    appLanguage: Int,
    onAppLanguageChange: (Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val backStack = remember { mutableStateListOf<Route>(Route.Main) }

    val isPagerBackHandlerEnabled by remember(backStack, pagerState.currentPage) {
        derivedStateOf {
            backStack.lastOrNull() is Route.Main && backStack.size == 1 && pagerState.currentPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    val firstBackCompleted: () -> Unit = {
        android.util.Log.d("HyperTweak", "First back completed. Pager scrolling to 0.")
        coroutineScope.launch {
            pagerState.scrollToPage(0)
        }
    }

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = firstBackCompleted
    )

    // Scale predictive back states
    var exitingPageKey by remember { mutableStateOf<String?>(null) }
    val exitAnimatable = remember { Animatable(0f) }
    // Non-Compose-state ref — avoids recomposition racing when navigating back
    // during an active enter transition (matches InstallerX's approach)
    val predictiveBackAnimState = remember { PredictiveBackAnimState() }

    var gestureState: NavigationEventState<SceneInfo<Route>>? = null

    val entryProvider = entryProvider<Route> {
        entry<Route.Main> {
            MainPagerScreen(
                pagerState = pagerState,
                useFloatingBottomBar = useFloatingBottomBar,
                floatingBarStyle = floatingBarStyle,
                backdrop = backdrop,
                moduleActive = moduleActive,
                hotReloadAvailable = hotReloadAvailable,
                hotReloading = hotReloading,
                hotReloadTargets = hotReloadTargets,
                hotReloadReport = hotReloadReport,
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
                showInSettings = showInSettings,
                onShowInSettingsChange = onShowInSettingsChange,
                hideLauncherIcon = hideLauncherIcon,
                onHideLauncherIconChange = onHideLauncherIconChange,
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
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useMonet = useMonet,
                onUseMonetChange = onUseMonetChange,
                seedColorHex = seedColorHex,
                onSeedColorChange = onSeedColorChange,
                onUseFloatingBottomBarChange = onUseFloatingBottomBarChange,
                onFloatingBarStyleChange = onFloatingBarStyleChange,
                predictiveBackStyle = predictiveBackStyle,
                onPredictiveBackStyleChange = onPredictiveBackStyleChange,
                predictiveBackFollowGesture = predictiveBackFollowGesture,
                onPredictiveBackFollowGestureChange = onPredictiveBackFollowGestureChange,
                allowLandscape = allowLandscape,
                onAllowLandscapeChange = onAllowLandscapeChange,
                pageScale = pageScale,
                onPageScaleChange = onPageScaleChange,
                onNavigateToAbout = {
                    backStack.add(Route.About)
                },
                onNavigateToDebugLogs = {
                    backStack.add(Route.DebugLogs)
                },
                onNavigateToHiddenFeatures = {
                    backStack.add(Route.HiddenFeatures)
                },
                onNavigateToAppShortcuts = {
                    backStack.add(Route.AppShortcuts)
                },
                onHotReload = onHotReload,
                onRestartScope = onRestartScope,
                appLanguage = appLanguage,
                onAppLanguageChange = onAppLanguageChange
            )
        }
        entry<Route.About> {
            AboutPage(
                onBack = {
                    if (backStack.size > 1) backStack.removeLast()
                },
                onViewSourceCode = onViewSourceCode,
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
        entry<Route.HiddenFeatures> {
            HiddenFeaturesPage(
                onBack = {
                    if (backStack.size > 1) backStack.removeLast()
                }
            )
        }
        entry<Route.AppShortcuts> {
            AppShortcutsPage(
                onBack = {
                    if (backStack.size > 1) backStack.removeLast()
                },
                onShortcutsChanged = onShortcutsChanged
            )
        }
        entry<Route.DebugLogs> {
            DebugLogPage(
                onBack = {
                    if (backStack.size > 1) backStack.removeLast()
                }
            )
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
                    exitingPageKey = exitingPageKey,
                    exitProgress = exitAnimatable.value,
                    animState = predictiveBackAnimState
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

    val onBack: (() -> Unit) -> Unit = { callBack ->
        coroutineScope.launch {
            val isPredictiveInProgress = gestureState?.transitionState is NavigationEventTransitionState.InProgress
            // Gate exitingPageKey exactly like InstallerX:
            // only trigger the exit slide animation when a real predictive gesture was actually running
            if (predictiveBackStyle == 2 && isPredictiveInProgress && predictiveBackAnimState.inPredictiveBackAnimation) {
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
            callBack()
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
        onBack = { onBack {} }
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
        onBack {}
    }

    NavigationBackHandler(
        state = gestureState,
        isBackEnabled = backStack.size > 1 && predictiveBackStyle != 0,
        onBackCompleted = { callBack ->
            android.util.Log.d("HyperTweak", "Second NavigationBackHandler completed. backStack size = ${backStack.size}")
            onBack(callBack)
        },
        onBackCancelled = { callBack ->
            callBack()
        }
    )

    NavDisplay(
        sceneState = sceneState,
        navigationEventState = gestureState,
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
                // If it's finishing a predictive back gesture, return None to avoid the double transition jump.
                if (exitingPageKey != null) {
                    ContentTransform(
                        targetContentEnter = EnterTransition.None,
                        initialContentExit = ExitTransition.None,
                        sizeTransform = null
                    )
                } else {
                    ContentTransform(
                        targetContentEnter = slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn(),
                        initialContentExit = scaleOut(targetScale = 0.9f) + fadeOut(),
                        sizeTransform = null
                    )
                }
            } else {
                defaultPopTransitionSpec<Route>().invoke(this)
            }
        }
    )
}
