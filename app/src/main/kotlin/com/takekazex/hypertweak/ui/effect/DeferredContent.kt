package com.takekazex.hypertweak.ui.effect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope

/**
 * Returns true only after the navigation transition animation has completed.
 * Used to defer loading of heavy Composable views during entry transitions.
 */
@Composable
fun rememberContentReady(): Boolean {
    val scope = LocalNavTransitionScope.current
    val transitionRunning = scope.isRunning
    val ready = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(transitionRunning) {
        if (!transitionRunning && !ready.value) {
            withFrameNanos { }
            ready.value = true
        }
    }

    return ready.value
}
