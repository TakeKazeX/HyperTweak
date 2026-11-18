package com.takekazex.hypertweak.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.*
import com.takekazex.hypertweak.settings.contract.HotReloadReport
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import com.takekazex.hypertweak.util.update.UpdateManager
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.nav.core.*

/** Current page values and callbacks supplied by the app shell on each composition. */
class NavigationSettings(

    // Module Settings States
    val moduleActive: Boolean,
    val hotReloadAvailable: Boolean,
    val hotReloading: Boolean,
    val hotReloadTargets: List<String>,
    val hotReloadReport: HotReloadReport?,
    val pendingRestartScopes: RestartScopeSelection,
    val hideLauncherIcon: Boolean,
    val onHideLauncherIconChange: (Boolean) -> Unit,

    // Backdrop
    val backdrop: LayerBackdrop,

    // Actions
    val onViewSourceCode: () -> Unit,
    val onJoinTelegramGroup: () -> Unit,
    val onClearAllSettings: (restartAllScopes: Boolean, restartHyperTweak: Boolean) -> Unit,
    val onSettingsRestored: () -> Unit,
    val onHotReload: () -> Unit,
    val onRestartAllScopes: () -> Unit,
    val onRestartScope: (RestartScopeSelection) -> Unit,
    val onShortcutsChanged: () -> Unit,
    val updateManager: UpdateManager,
    val snackbarHostState: SnackbarHostState
)
