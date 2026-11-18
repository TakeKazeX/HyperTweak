package com.takekazex.hypertweak.ui.page.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.components.dialog.AppWindowDialog
import com.takekazex.hypertweak.util.backup.SettingsBackupDocument
import com.takekazex.hypertweak.util.logging.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun BackupRestorePage(
    onBack: () -> Unit,
    onClearAllSettings: (restartAllScopes: Boolean, restartHyperTweak: Boolean) -> Unit,
    onSettingsRestored: () -> Unit
) {
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
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
                    val json = settingsStore.backup.exportSettings()
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
                    val json = resolver.openInputStream(uri)?.use(SettingsBackupDocument::read)
                        ?: error("Unable to open the selected file")
                    settingsStore.backup.restoreSettings(json)
                }
            }
            busy = false
            result.onSuccess { restoredCount ->
                Toast.makeText(
                    context,
                    resources.getQuantityString(
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

    AppScrollPage(
        title = stringResource(R.string.settings_backup_restore),
        onBack = onBack,
        backContentDescription = stringResource(R.string.debug_back),
    ) {
        AppSection(title = stringResource(R.string.settings_backup_restore_title)) {
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

        AppSection(title = stringResource(R.string.settings_danger_zone)) {
            ArrowPreference(
                title = stringResource(R.string.settings_clear_all_settings),
                summary = stringResource(R.string.settings_clear_all_settings_summary),
                enabled = !busy,
                onClick = { showClearDialog = true }
            )
        }
    }

    AppWindowDialog(
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

    AppWindowDialog(
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
                        destructive = true
                    )
                }
            }
        }
    )
}
