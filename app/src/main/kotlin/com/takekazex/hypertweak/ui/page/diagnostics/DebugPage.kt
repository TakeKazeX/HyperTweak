package com.takekazex.hypertweak.ui.page.diagnostics

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.BackGestureKeys
import com.takekazex.hypertweak.settings.contract.HotReloadReport
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.dialog.HotReloadDialog
import com.takekazex.hypertweak.util.diagnostics.TestNotifier
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun DebugPage(
    onBack: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToWirelessAdb: () -> Unit,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    onHotReload: () -> Unit,
    onRestartAllScopes: () -> Unit
) {
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current
    var upstreamDetectionDisabled by rememberStoredSetting(BackGestureKeys.KEY_BACK_GESTURE_UPSTREAM_DETECTION_DISABLED, settingsStore::putBoolean) { settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_UPSTREAM_DETECTION_DISABLED, false) }
    var recordLogs by rememberStoredSetting(PlatformSettingsKeys.KEY_RECORD_LOGS, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_RECORD_LOGS, true) }
    var clipboardReadSucceeded by remember { mutableStateOf<Boolean?>(null) }
    var showHotReloadDialog by remember { mutableStateOf(false) }
    // Posts via `su`/shell (see TestNotifier); no runtime permission is needed because the
    // notification is posted by the shell uid, not the module app process.
    val postTestNotifications: (Int) -> Unit = { count -> TestNotifier.post(context, count) }
    AppScrollPage(
        title = stringResource(R.string.debug_page_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.debug_back),
    ) {
        AppSection(title = stringResource(R.string.debug_diagnostics_title)) {
            SwitchPreference(
                checked = recordLogs,
                onCheckedChange = { enabled ->
                    recordLogs = enabled
                },
                title = stringResource(R.string.debug_record_logs_title),
                summary = stringResource(R.string.debug_record_logs_summary)
            )
            SwitchPreference(
                title = stringResource(R.string.back_gesture_upstream_detection_disabled_title),
                summary = stringResource(R.string.back_gesture_upstream_detection_disabled_summary),
                checked = upstreamDetectionDisabled,
                onCheckedChange = {
                    upstreamDetectionDisabled = it
                }
            )
            ArrowPreference(
                title = stringResource(R.string.debug_logs_title),
                summary = stringResource(R.string.debug_logs_summary),
                onClick = onNavigateToLogs
            )
        }
        DebugAccessSection()
        AppSection(title = stringResource(R.string.debug_actions_title)) {
            ArrowPreference(
                title = stringResource(R.string.debug_adb_tcp_title),
                summary = stringResource(R.string.debug_adb_tcp_summary),
                onClick = onNavigateToWirelessAdb,
            )
            Button(
                onClick = { showHotReloadDialog = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.debug_hot_reload_button))
            }
            Button(
                onClick = { launchBiometricAuthentication(context) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.debug_biometric_button))
            }
            Button(
                onClick = { clipboardReadSucceeded = readClipboard(context) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.debug_read_clipboard_button))
            }
            clipboardReadSucceeded?.let { succeeded ->
                Text(
                    text = stringResource(
                        if (succeeded) {
                            R.string.debug_clipboard_read_success
                        } else {
                            R.string.debug_clipboard_read_failed
                        }
                    ),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                )
            }
        }
        AppSection(title = stringResource(R.string.debug_notification_test_title)) {
            Button(
                onClick = { postTestNotifications(1) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.debug_notification_test_one))
            }
            Button(
                onClick = { postTestNotifications(3) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.debug_notification_test_three))
            }
            Button(
                onClick = {
                    Toast.makeText(
                        context,
                        R.string.debug_toast_test,
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.debug_toast_test))
            }
        }

        HotReloadDialog(
            show = showHotReloadDialog,
            hotReloading = hotReloading,
            targets = hotReloadTargets,
            lastReport = hotReloadReport,
            onDismissRequest = { showHotReloadDialog = false },
            onHotReload = onHotReload,
            onRestartScopes = onRestartAllScopes
        )
    }
}

private fun launchBiometricAuthentication(context: Context) {
    val activity = context.findDebugActivity()
    if (activity == null) {
        showDebugToast(context, R.string.debug_biometric_unavailable)
        return
    }

    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
    val biometricManager = activity.getSystemService(BiometricManager::class.java)
    val availability = runCatching {
        biometricManager?.canAuthenticate(authenticators)
    }.getOrNull()
    if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
        val messageRes = when (availability) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> R.string.debug_biometric_no_hardware
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.debug_biometric_not_enrolled
            else -> R.string.debug_biometric_unavailable
        }
        showDebugToast(context, messageRes)
        return
    }

    runCatching {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(context.getString(R.string.debug_biometric_title))
            .setSubtitle(context.getString(R.string.debug_biometric_subtitle))
            .setAllowedAuthenticators(authenticators)
            .setNegativeButton(
                context.getString(R.string.debug_biometric_cancel),
                executor
            ) { _, _ -> }
            .build()
        prompt.authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    showDebugToast(context, R.string.debug_biometric_success)
                }

                override fun onAuthenticationFailed() {
                    showDebugToast(context, R.string.debug_biometric_failed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showDebugToast(context, R.string.debug_biometric_error, errString)
                }
            }
        )
    }.onFailure {
        showDebugToast(context, R.string.debug_biometric_unavailable)
    }
}

private fun readClipboard(context: Context): Boolean {
    return runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            false
        } else {
            // Force the actual item read so the system clipboard-read path is exercised without
            // exposing the clipboard contents in the debug UI or in a Toast.
            clip.getItemAt(0).coerceToText(context)
            true
        }
    }.getOrDefault(false)
}

private fun showDebugToast(context: Context, messageRes: Int, vararg args: Any) {
    Toast.makeText(
        context,
        context.getString(messageRes, *args),
        Toast.LENGTH_LONG
    ).show()
}

internal tailrec fun Context.findDebugActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findDebugActivity()
    else -> null
}
