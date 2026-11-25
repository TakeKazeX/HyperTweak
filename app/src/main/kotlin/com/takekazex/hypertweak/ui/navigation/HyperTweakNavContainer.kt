package com.takekazex.hypertweak.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.ui.chrome.AppUiStyle
import com.takekazex.hypertweak.ui.chrome.LocalAppUiStyle
import com.takekazex.hypertweak.ui.chrome.PageBackdropBox
import com.takekazex.hypertweak.ui.effect.scaleBackTransition
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.saveKey
import top.yukonga.miuix.kmp.nav.core.*
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

@Composable
fun HyperTweakNavContainer(
    settings: NavigationSettings
) = with(settings) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val mainPagerState = com.takekazex.hypertweak.ui.liquid.rememberMainPagerState(pagerState)
    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
    }
    // Persist the back stack across process death: routes are data objects, so serialize them to
    // their stable keys and rebuild the observable list on restore. Falls back to [Route.Main] if
    // nothing (or only unknown keys) was saved, keeping the same initial entry as a fresh launch.
    val backStack = rememberSaveable(
        saver = listSaver(
            save = { stack -> stack.map { (it as Route).saveKey } },
            restore = { keys ->
                restoreNavigationRoutes(keys).toMutableStateList<NavKey>()
            }
        )
    ) { mutableStateListOf<NavKey>(Route.Main) }

    val navController = remember(backStack) { NavController(backStack) }

    val isPagerBackHandlerEnabled by remember(backStack, mainPagerState.selectedPage) {
        computedStateOf {
            backStack.lastOrNull() is Route.Main && backStack.size == 1 && mainPagerState.selectedPage != 0
        }
    }

    BackHandler(enabled = isPagerBackHandlerEnabled) {
        mainPagerState.animateToPage(0)
    }

    val entryProvider: NavEntryBuilder.() -> Unit = {
        mainPages(settings, navController, mainPagerState)
        systemPages(settings, navController)
        featuresPages(settings, navController)
        toolsPages(settings, navController)
    }

    val appSettings = com.takekazex.hypertweak.ui.app.LocalAppSettings.current
    val predictiveBackStyle = appSettings.predictiveBackStyle
    val predictiveBackFollowGesture = appSettings.predictiveBackFollowGesture
    val scaleTransition = remember(predictiveBackFollowGesture) {
        scaleBackTransition(predictiveBackFollowGesture)
    }
    val debugRevision by com.takekazex.hypertweak.util.diagnostics.DebugSettingsEvents.revision.collectAsStateWithLifecycle()
    val navDisplay: @Composable () -> Unit = {
    NavDisplay(
        backStack = backStack,
        onBack = { navController.pop() },
        transition = when (predictiveBackStyle) {
            0 -> NavTransitions.None
            2 -> scaleTransition
            else -> NavTransitions.MiuixDefault
        },
        effects = if (predictiveBackStyle == 0) NavDisplayEffects.None else NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
            cornerClipMode = if (predictiveBackStyle == 2) NavCornerClipMode.All else NavCornerClipMode.Leading,
        ),
        content = entryProvider,
    )
    }
    key(debugRevision) {
        // OS4 glass overlays (dialogs, sheets, dropdown popups) sample the whole page, bars
        // included; the top bar samples its own content-only recording (see AppPage). Overlays
        // render in the root Scaffold's popup host, so that Scaffold must sit outside the
        // recording (upstream Mishka layout): an overlay inside the layer it samples recurses
        // in RenderThread until the stack overflows.
        // The tree is the same in both styles, so switching style keeps NavDisplay state; OS3
        // only skips the recording.
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0)) {
            PageBackdropBox(Modifier.fillMaxSize(), enabled = LocalAppUiStyle.current == AppUiStyle.Os4, content = navDisplay)
        }
    }
}
