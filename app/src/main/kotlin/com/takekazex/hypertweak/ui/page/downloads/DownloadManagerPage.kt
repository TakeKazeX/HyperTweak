package com.takekazex.hypertweak.ui.page.downloads

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.chrome.AppOverlayDialog as OverlayDialog
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.ui.state.LocalDownloadXlCleanup
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Download Manager UI and provider tweaks kept together on a dedicated second-level page. */
@Composable
fun DownloadManagerPage(
    onBack: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val cleanup = LocalDownloadXlCleanup.current
    val cleanupState by cleanup.state.collectAsStateWithLifecycle()

    fun checkDirectory() {
        cleanup.checkAndEnable({ appSettings.blockDownloadXlLogDir = it }) {
            Toast.makeText(context, resources.getString(R.string.download_manager_xl_check_failed), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(appSettings.blockDownloadXlLogDir) {
        if (!appSettings.blockDownloadXlLogDir) cleanup.dismiss()
    }

    fun handleBlockDownloadXlLogDirChange(checked: Boolean) {
        if (checked) checkDirectory()
        else cleanup.disable({ appSettings.blockDownloadXlLogDir = it })
    }

    AppScrollPage(
        title = stringResource(R.string.download_manager_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.download_manager_back),
    ) {
        MissingScopeCard("com.android.providers.downloads", "com.android.providers.downloads.ui")

        AppSection(title = stringResource(R.string.download_manager_section_general)) {
            SwitchPreference(
                checked = appSettings.downloadAlwaysShowFullLink,
                onCheckedChange = { appSettings.downloadAlwaysShowFullLink = it },
                title = stringResource(R.string.download_manager_always_show_link_title),
                summary = stringResource(R.string.download_manager_always_show_link_summary)
            )
            SwitchPreference(
                checked = appSettings.downloadHideXl,
                onCheckedChange = { appSettings.downloadHideXl = it },
                title = stringResource(R.string.download_manager_hide_xl_title),
                summary = stringResource(R.string.download_manager_hide_xl_summary)
            )
            SwitchPreference(
                checked = appSettings.downloadAddNewButton,
                onCheckedChange = { appSettings.downloadAddNewButton = it },
                title = stringResource(R.string.download_manager_add_new_button_title),
                summary = stringResource(R.string.download_manager_add_new_button_summary)
            )
            SwitchPreference(
                checked = appSettings.downloadFocusNotification,
                onCheckedChange = { appSettings.downloadFocusNotification = it },
                title = stringResource(R.string.download_manager_focus_notification_title),
                summary = stringResource(R.string.download_manager_focus_notification_summary)
            )
            SwitchPreference(
                checked = appSettings.blockDownloadXlLogDir,
                enabled = !cleanupState.busy,
                onCheckedChange = ::handleBlockDownloadXlLogDirChange,
                title = stringResource(R.string.download_manager_block_xl_title),
                summary = stringResource(if (cleanupState.busy) R.string.download_manager_xl_busy
                    else R.string.download_manager_block_xl_summary)
            )
        }

        Spacer(Modifier.height(48.dp))


        OverlayDialog(
            show = cleanupState.confirmDelete,
            title = stringResource(R.string.download_manager_xl_dialog_title),
            summary = stringResource(R.string.download_manager_xl_dialog_summary),
            onDismissRequest = { cleanup.dismiss() },
            content = {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(R.string.download_manager_xl_keep),
                        onClick = { cleanup.dismiss() },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.download_manager_xl_delete),
                        onClick = {
                            cleanup.confirm { deleted ->
                                val message = if (deleted) R.string.download_manager_xl_delete_success
                                    else R.string.download_manager_xl_delete_failed
                                Toast.makeText(context, resources.getString(message), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }
}
