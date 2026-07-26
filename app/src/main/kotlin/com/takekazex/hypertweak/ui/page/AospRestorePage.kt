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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.hook.Preferences
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
    var extendUnlockFix by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_EXTEND_UNLOCK_FIX, false))
    }
    var clipboardEditor by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_CLIPBOARD_EDITOR, false))
    }
    // These switches are not hoisted into MainActivity, so they do not feed the pending-restart
    // tracking; offer the restart here instead once something on this page needs one.
    var systemUiRestartPending by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = "AOSP Restore",
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "Back") } }
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

            SmallTitle("System")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = packageInstaller,
                        onCheckedChange = { enabled ->
                            packageInstaller = enabled
                            Preferences.putBoolean(Preferences.KEY_AOSP_PACKAGE_INSTALLER, enabled)
                        },
                        title = "AOSP Package Installer",
                        summary = "Install packages with the AOSP installer instead of the MIUI one. " +
                            "Relaxes MIUI install verification. Requires a reboot"
                    )
                }
            }

            SmallTitle("System UI")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = powerMenu,
                        onCheckedChange = { enabled ->
                            powerMenu = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_POWER_MENU, enabled)
                        },
                        title = "AOSP Power Menu",
                        summary = "Hide MIUI's global-actions plugin so the AOSP power menu is used. " +
                            "Requires a SystemUI restart"
                    )
                    SwitchPreference(
                        checked = volumePanel,
                        onCheckedChange = { enabled ->
                            volumePanel = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_VOLUME_PANEL, enabled)
                        },
                        title = "AOSP Volume Panel",
                        summary = "Hide MIUI's volume-dialog plugin so the AOSP volume panel is used. " +
                            "Other volume slider tweaks do not apply to it. Requires a SystemUI restart"
                    )
                    SwitchPreference(
                        checked = extendUnlockFix,
                        onCheckedChange = { enabled ->
                            extendUnlockFix = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_EXTEND_UNLOCK_FIX, enabled)
                        },
                        title = "Extend Unlock",
                        summary = "Re-derive keyguard trust from the system when HyperOS lets its " +
                            "cached trust state go stale. Requires a SystemUI restart"
                    )
                    SwitchPreference(
                        checked = clipboardEditor,
                        onCheckedChange = { enabled ->
                            clipboardEditor = enabled
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_AOSP_CLIPBOARD_EDITOR, enabled)
                        },
                        title = "AOSP Clipboard Editor",
                        summary = "Show the AOSP clipboard overlay when copying. HyperOS keeps " +
                            "showing its own editor as well. Requires a SystemUI restart"
                    )
                    if (systemUiRestartPending) {
                        ArrowPreference(
                            title = "Restart SystemUI",
                            summary = "Apply the changes made on this page",
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

            SmallTitle("Input Method")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = "AOSP Keyboard Bar",
                    summary = "Draw the AOSP gesture navigation bar inside selected keyboards",
                    onClick = onNavigateToAospIme
                )
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
