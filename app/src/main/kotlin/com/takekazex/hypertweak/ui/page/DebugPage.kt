package com.takekazex.hypertweak.ui.page

import android.app.Activity
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.HotReloadReport
import com.takekazex.hypertweak.util.PlatformLevel
import com.takekazex.hypertweak.util.TestNotifier
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun DebugPage(
    onBack: () -> Unit,
    onNavigateToLogs: () -> Unit,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    onHotReload: () -> Unit,
    onRestartAllScopes: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    var recordLogs by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_RECORD_LOGS, true)) }
    var aospBackLogs by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_BACK_LOGS, false)) }
    var clipboardReadSucceeded by remember { mutableStateOf<Boolean?>(null) }
    var showHotReloadDialog by remember { mutableStateOf(false) }
    // Posts via `su`/shell (see TestNotifier); no runtime permission is needed because the
    // notification is posted by the shell uid, not the module app process.
    val postTestNotifications: (Int) -> Unit = { count -> TestNotifier.post(context, count) }
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
            SmallTitle(stringResource(R.string.debug_actions_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
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
            }
            SmallTitle(stringResource(R.string.debug_notification_test_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
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
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
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
    val activity = context.findActivity()
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
