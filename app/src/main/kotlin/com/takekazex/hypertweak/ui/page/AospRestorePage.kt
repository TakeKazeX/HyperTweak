package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.ExtendUnlockLauncher
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Toggles that hand a HyperOS component back to its AOSP implementation.
 *
 * State is kept locally rather than hoisted into `MainActivity`, so these switches do not feed the
 * pending-restart-scope tracking; the page surfaces the restart requirement in each summary.
 */
@Composable
fun AospRestorePage(onBack: () -> Unit, onNavigateToAospIme: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var packageInstaller by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_PACKAGE_INSTALLER, false))
    }
    var powerMenu by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_POWER_MENU, false))
    }
    var volumePanel by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_VOLUME_PANEL, false))
    }
    var volumePanelHapticMiui by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_VOLUME_HAPTIC_MIUI, true))
    }
    var extendUnlockFix by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_EXTEND_UNLOCK_FIX, false))
    }
    var clipboardEditor by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_CLIPBOARD_EDITOR, false))
    }
    var appInfoEntry by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_APP_INFO_ENTRY, false))
    }
    var appManagerEntry by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_APP_MANAGER_ENTRY, false))
    }
    // These switches are not hoisted into MainActivity, so they do not feed the pending-restart
    // tracking; offer the restart here instead once something on this page needs one. Saveable so
    // the pending prompt survives navigating away (Nav3 disposes the entry) and process death.
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }
    var securityCenterRestartPending by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.aosp_page_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.aosp_back)) } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

            SmallTitle(stringResource(R.string.aosp_section_system))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = packageInstaller,
                        onCheckedChange = { enabled ->
                            packageInstaller = enabled
                            Preferences.putBoolean(Preferences.KEY_AOSP_PACKAGE_INSTALLER, enabled)
                        },
                        title = stringResource(R.string.aosp_package_installer),
                        summary = stringResource(R.string.aosp_package_installer_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.aosp_section_system_ui))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = powerMenu,
                        onCheckedChange = { enabled ->
                            powerMenu = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_POWER_MENU, enabled)
                        },
                        title = stringResource(R.string.aosp_power_menu),
                        summary = stringResource(R.string.aosp_power_menu_summary)
                    )
                    SwitchPreference(
                        checked = volumePanel,
                        onCheckedChange = { enabled ->
                            volumePanel = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_VOLUME_PANEL, enabled)
                        },
                        title = stringResource(R.string.aosp_volume_panel),
                        summary = stringResource(R.string.aosp_volume_panel_summary)
                    )
                    SwitchPreference(
                        checked = volumePanelHapticMiui,
                        onCheckedChange = { enabled ->
                            volumePanelHapticMiui = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_VOLUME_HAPTIC_MIUI, enabled)
                        },
                        enabled = volumePanel,
                        title = stringResource(R.string.aosp_volume_haptic_miui),
                        summary = stringResource(R.string.aosp_volume_haptic_miui_summary)
                    )
                    SwitchPreference(
                        checked = extendUnlockFix,
                        onCheckedChange = { enabled ->
                            extendUnlockFix = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_EXTEND_UNLOCK_FIX, enabled)
                        },
                        title = stringResource(R.string.aosp_extend_unlock),
                        summary = stringResource(R.string.aosp_extend_unlock_summary)
                    )
                    ArrowPreference(
                        title = stringResource(R.string.aosp_configure_extend_unlock),
                        summary = stringResource(R.string.aosp_configure_extend_unlock_summary),
                        enabled = extendUnlockFix,
                        onClick = { ExtendUnlockLauncher.launch(context) }
                    )
                    SwitchPreference(
                        checked = clipboardEditor,
                        onCheckedChange = { enabled ->
                            clipboardEditor = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_CLIPBOARD_EDITOR, enabled)
                        },
                        title = stringResource(R.string.aosp_clipboard_editor),
                        summary = stringResource(R.string.aosp_clipboard_editor_summary)
                    )
                    if (systemUiRestartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.aosp_restart_system_ui),
                            summary = stringResource(R.string.aosp_restart_system_ui_summary),
                            onClick = {
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(systemUi = true)
                                )
                                systemUiRestartPending = false
                            }
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.aosp_section_security_center))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = appInfoEntry,
                        onCheckedChange = { enabled ->
                            appInfoEntry = enabled
                            securityCenterRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_APP_INFO_ENTRY, enabled)
                        },
                        title = stringResource(R.string.aosp_app_info_entry),
                        summary = stringResource(R.string.aosp_app_info_entry_summary)
                    )
                    SwitchPreference(
                        checked = appManagerEntry,
                        onCheckedChange = { enabled ->
                            appManagerEntry = enabled
                            securityCenterRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_APP_MANAGER_ENTRY, enabled)
                        },
                        title = stringResource(R.string.aosp_app_manager_entry),
                        summary = stringResource(R.string.aosp_app_manager_entry_summary)
                    )
                    if (securityCenterRestartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.aosp_restart_security_center),
                            summary = stringResource(R.string.aosp_restart_security_center_summary),
                            onClick = {
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(securityCenter = true)
                                )
                                securityCenterRestartPending = false
                            }
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.aosp_section_input_method))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.aosp_keyboard_bar),
                    summary = stringResource(R.string.aosp_keyboard_bar_summary),
                    onClick = onNavigateToAospIme
                )
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
