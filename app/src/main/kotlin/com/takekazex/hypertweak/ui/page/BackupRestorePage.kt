package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("LocalContextResourcesRead")
@Composable
fun BackupRestorePage(
    onBack: () -> Unit,
    onClearAllSettings: (restartAllScopes: Boolean, restartHyperTweak: Boolean) -> Unit,
    onSettingsRestored: () -> Unit
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    var busy by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showClearConfirmationDialog by remember { mutableStateOf(false) }
    var restartAllScopesAfterClear by remember(showClearConfirmationDialog) { mutableStateOf(true) }
    var restartHyperTweakAfterClear by remember(showClearConfirmationDialog) { mutableStateOf(true) }
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = Preferences.exportSettings()
                    resolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: error("Unable to open the selected file")
                }
            }
            busy = false
            result.onSuccess {
                Toast.makeText(
                    context,
                    R.string.settings_backup_success,
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { failure ->
                DebugLog.w("BackupRestore", "settings backup failed", failure)
                Toast.makeText(
                    context,
                    R.string.settings_backup_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader?.readText() ?: error("Unable to open the selected file")
                    }
                    Preferences.restoreSettings(json)
                }
            }
            busy = false
            result.onSuccess { restoredCount ->
                Toast.makeText(
                    context,
                    context.resources.getQuantityString(
                        R.plurals.settings_restore_success,
                        restoredCount,
                        restoredCount
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                onSettingsRestored()
            }.onFailure { failure ->
                DebugLog.w("BackupRestore", "settings restore failed", failure)
                Toast.makeText(
                    context,
                    R.string.settings_restore_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_backup_restore),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            MiuixIcons.Back,
                            contentDescription = stringResource(R.string.debug_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            SmallTitle(stringResource(R.string.settings_backup_restore_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_backup),
                        summary = stringResource(R.string.settings_backup_summary),
                        enabled = !busy,
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                                .format(Date())
                            createBackupLauncher.launch("HyperTweak-backup-$timestamp.json")
                        }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_restore),
                        summary = stringResource(R.string.settings_restore_summary),
                        enabled = !busy,
                        onClick = {
                            openBackupLauncher.launch(arrayOf("application/json", "text/plain"))
                        }
                    )
                }
            }

            SmallTitle(stringResource(R.string.settings_danger_zone))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_clear_all_settings),
                    summary = stringResource(R.string.settings_clear_all_settings_summary),
                    enabled = !busy,
                    onClick = { showClearDialog = true }
                )
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }

    WindowDialog(
        show = showClearDialog,
        title = stringResource(R.string.settings_clear_all_settings),
        summary = stringResource(R.string.settings_clear_all_dialog_summary),
        onDismissRequest = { showClearDialog = false },
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.settings_cancel),
                    onClick = { showClearDialog = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.settings_continue),
                    onClick = {
                        showClearDialog = false
                        showClearConfirmationDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )

    WindowDialog(
        show = showClearConfirmationDialog,
        title = stringResource(R.string.settings_clear_all_settings),
        summary = stringResource(R.string.settings_clear_all_dialog_second_summary),
        onDismissRequest = { showClearConfirmationDialog = false },
        content = {
            Column(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = restartAllScopesAfterClear,
                    onCheckedChange = { restartAllScopesAfterClear = it },
                    title = stringResource(R.string.settings_clear_restart_all_scopes),
                    summary = stringResource(R.string.settings_clear_restart_all_scopes_summary)
                )
                SwitchPreference(
                    checked = restartHyperTweakAfterClear,
                    onCheckedChange = { restartHyperTweakAfterClear = it },
                    title = stringResource(R.string.settings_clear_restart_hypertweak),
                    summary = stringResource(R.string.settings_clear_restart_hypertweak_summary)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(R.string.settings_cancel),
                        onClick = { showClearConfirmationDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.settings_clear),
                        onClick = {
                            showClearConfirmationDialog = false
                            onClearAllSettings(
                                restartAllScopesAfterClear,
                                restartHyperTweakAfterClear
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(
                            color = Color(0xFFD32F2F),
                            disabledColor = Color(0xFF6D3434),
                            textColor = Color.White,
                            disabledTextColor = Color(0xFFBDBDBD)
                        )
                    )
                }
            }
        }
    )
}
