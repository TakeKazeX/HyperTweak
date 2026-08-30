package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.PlatformLevel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun DebugPage(onBack: () -> Unit, onNavigateToLogs: () -> Unit, onClearAllSettings: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    var recordLogs by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_RECORD_LOGS, true)) }
    var aospBackLogs by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_BACK_LOGS, false)) }
    // Two-step "clear all settings" escalation: the first dialog is a normal confirmation; the
    // second is an extra strong confirm before the destructive clear actually runs.
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showClearAllSecondDialog by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.debug_page_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.debug_back)) } }
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
            SmallTitle(stringResource(R.string.debug_diagnostics_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = recordLogs,
                        onCheckedChange = { enabled ->
                            recordLogs = enabled
                            Preferences.putBoolean(Preferences.KEY_RECORD_LOGS, enabled)
                        },
                        title = stringResource(R.string.debug_record_logs_title),
                        summary = stringResource(R.string.debug_record_logs_summary)
                    )
                    if (!PlatformLevel.isOs4) {
                        SwitchPreference(
                            checked = aospBackLogs,
                            onCheckedChange = { enabled ->
                                aospBackLogs = enabled
                                Preferences.putBoolean(Preferences.KEY_AOSP_BACK_LOGS, enabled)
                            },
                            title = stringResource(R.string.debug_aosp_back_gesture_logs_title),
                            summary = stringResource(R.string.debug_aosp_back_gesture_logs_summary)
                        )
                    }
                    ArrowPreference(
                        title = stringResource(R.string.debug_logs_title),
                        summary = stringResource(R.string.debug_logs_summary),
                        onClick = onNavigateToLogs
                    )
                }
            }
            SmallTitle(stringResource(R.string.settings_clear_all_settings))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_clear_all_settings),
                    summary = stringResource(R.string.settings_clear_all_settings_summary),
                    onClick = { showClearAllDialog = true }
                )
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }

    OverlayDialog(
        show = showClearAllDialog,
        title = stringResource(R.string.settings_clear_all_settings),
        summary = stringResource(R.string.settings_clear_all_dialog_summary),
        onDismissRequest = { showClearAllDialog = false },
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.settings_cancel),
                    onClick = { showClearAllDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.settings_clear),
                    onClick = {
                        showClearAllDialog = false
                        showClearAllSecondDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )

    OverlayDialog(
        show = showClearAllSecondDialog,
        title = stringResource(R.string.settings_clear_all_settings),
        summary = stringResource(R.string.settings_clear_all_dialog_summary),
        onDismissRequest = { showClearAllSecondDialog = false },
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.settings_cancel),
                    onClick = { showClearAllSecondDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.settings_clear),
                    onClick = {
                        showClearAllSecondDialog = false
                        onClearAllSettings()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )
}
