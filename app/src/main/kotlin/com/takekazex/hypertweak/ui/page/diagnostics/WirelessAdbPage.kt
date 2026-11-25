package com.takekazex.hypertweak.ui.page.diagnostics

import com.takekazex.hypertweak.util.logging.DebugLog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.dialog.AppWindowDialog
import com.takekazex.hypertweak.util.platform.AdbTcpPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WirelessAdbPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val options = remember(appContext) { AdbTcpPort.options(appContext) }
    var custom by rememberSaveable { mutableStateOf(options.getBoolean("custom", false)) }
    val input = rememberTextFieldState(options.getString("port", "").orEmpty())
    var state by remember { mutableStateOf<AdbTcpPort.State?>(null) }
    var working by remember { mutableStateOf(false) }
    var authentication by remember { mutableStateOf<CancellationSignal?>(null) }
    var initialized by remember { mutableStateOf(false) }
    val customPort = input.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
    val port = if (custom) customPort ?: -1 else 0
    val bound = state?.bound.orEmpty()
    var removing by remember { mutableStateOf<AdbTcpPort.Wifi?>(null) }
    val current = state?.current
    val endpoint = state?.endpoint
    val listening = endpoint != null
    val usingSystem = state?.systemTls == true
    val restartMode = state?.restartMode ?: AdbTcpPort.RestartMode.OFF
    val randomOnly = restartMode == AdbTcpPort.RestartMode.ALWAYS_RANDOM
    val bindTitle = stringResource(R.string.debug_adb_tcp_bind_title)
    val cancelLabel = stringResource(R.string.debug_biometric_cancel)
    val endpointLabel = stringResource(R.string.debug_adb_tcp_endpoint)
    val canEdit = initialized && state != null && !working && authentication == null

    LaunchedEffect(input, options) {
        snapshotFlow { input.text.toString() }.collect { portText ->
            if (options.getString("port", "").orEmpty() != portText) {
                options.edit { putString("port", portText) }
            }
        }
    }

    suspend fun initialize() {
        working = true
        try {
            initialized = AdbTcpPort.restore(appContext)
            state = if (initialized) AdbTcpPort.read(appContext) else null
        } finally {
            working = false
        }
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!initialized) initialize()
            while (true) {
                delay(if (state?.endpoint != null) 1500 else 3500)
                if (initialized && !working) state = AdbTcpPort.read(appContext)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { authentication?.cancel() }
    }

    LaunchedEffect(initialized, port, usingSystem) {
        if (initialized && !usingSystem) {
            delay(350)
            AdbTcpPort.updatePort(port)
        }
    }

    fun change(action: suspend () -> Boolean) {
        working = true
        scope.launch {
            val success = action()
            state = AdbTcpPort.read(appContext)
            working = false
            if (!success) Toast.makeText(context, R.string.debug_adb_tcp_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun authenticate(network: AdbTcpPort.Wifi) {
        val activity = context.findDebugActivity()
        val strength = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val manager = activity?.getSystemService(BiometricManager::class.java)
        if (activity == null || manager?.canAuthenticate(strength) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(context, R.string.debug_biometric_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val signal = CancellationSignal()
        authentication = signal
        val executor = activity.mainExecutor
        runCatching {
            BiometricPrompt.Builder(activity)
                .setTitle(bindTitle)
                .setSubtitle("${network.name} · ${network.bssid}")
                .setAllowedAuthenticators(strength)
                .setNegativeButton(cancelLabel, executor) { _, _ -> }
                .build()
                .authenticate(signal, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authentication = null
                        if (!signal.isCanceled) change { AdbTcpPort.addBinding(network) }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        authentication = null
                    }
                })
        }.onFailure {
            authentication = null
            Toast.makeText(context, R.string.debug_biometric_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    val currentBound = current != null && current in bound
    AppScrollPage(
        title = stringResource(R.string.debug_adb_tcp_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.debug_back),
    ) {
        AppSection {
            OverlayDropdownPreference(
                title = stringResource(R.string.debug_adb_tcp_transport),
                summary = stringResource(
                    if (usingSystem) R.string.debug_adb_tcp_transport_system_summary
                    else R.string.debug_adb_tcp_transport_legacy_summary
                ),
                items = listOf(
                    stringResource(R.string.debug_adb_tcp_transport_legacy),
                    stringResource(R.string.debug_adb_tcp_transport_system),
                ),
                selectedIndex = if (usingSystem) 1 else 0,
                enabled = canEdit,
                onSelectedIndexChange = { index -> change { AdbTcpPort.setSystemTls(index == 1) } },
            )
            if (usingSystem) {
                ArrowPreference(
                    title = stringResource(R.string.debug_adb_tcp_open_system),
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }.onFailure { DebugLog.w("WirelessAdb", "open developer options failed", it) }
                    },
                )
            }
        }
        if (!usingSystem) {
            Spacer(Modifier.height(8.dp))
            AppSection {
                SwitchPreference(
                    title = stringResource(R.string.debug_adb_tcp_manual),
                    summary = stringResource(R.string.debug_adb_tcp_manual_summary),
                    checked = listening,
                    enabled = canEdit && (listening || (current != null && (port >= 0 || randomOnly))),
                    onCheckedChange = { enabled -> change { AdbTcpPort.setEnabled(enabled, current, port) } },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AppSection {
            val copyModifier = if (endpoint == null) Modifier else Modifier.combinedClickable(
                    onClick = {},
                    onLongClickLabel = stringResource(R.string.debug_adb_tcp_copy),
                    onLongClick = {
                        scope.launch {
                            val verified = AdbTcpPort.read(appContext)?.endpoint
                            if (verified == endpoint) {
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                    ClipData.newPlainText(endpointLabel, verified)
                                )
                            }
                        }
                    },
            )
            Column(Modifier.fillMaxWidth().then(copyModifier).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = endpointLabel,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Text(
                    text = when {
                        endpoint != null -> endpoint
                        working -> stringResource(R.string.debug_adb_tcp_working)
                        state == null -> stringResource(R.string.debug_adb_tcp_unknown)
                        state?.failure == 2 -> stringResource(R.string.debug_adb_tcp_occupied)
                        state?.failure != null -> stringResource(R.string.debug_adb_tcp_failed)
                        else -> stringResource(R.string.debug_adb_tcp_offline)
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (!working && state == null) {
                ArrowPreference(
                    title = stringResource(R.string.debug_adb_tcp_retry),
                    onClick = { scope.launch { initialize() } },
                )
            }
        }
        if (!usingSystem) {
            Spacer(Modifier.height(8.dp))
            AppSection(title = stringResource(R.string.debug_adb_tcp_bound_title, bound.size, AdbTcpPort.MAX_BOUND)) {
                if (bound.isEmpty()) {
                    BasicComponent(summary = stringResource(R.string.debug_adb_tcp_bound_empty))
                }
                bound.forEach { network ->
                    ArrowPreference(
                        title = network.name,
                        summary = if (network == current) {
                            stringResource(R.string.debug_adb_tcp_bound_current, network.bssid)
                        } else network.bssid,
                        enabled = canEdit,
                        onClick = { removing = network },
                    )
                }
                when {
                    current == null -> BasicComponent(
                        summary = stringResource(R.string.debug_adb_tcp_wifi_unavailable),
                    )
                    currentBound -> Unit
                    bound.size >= AdbTcpPort.MAX_BOUND -> BasicComponent(
                        title = stringResource(R.string.debug_adb_tcp_bind_current, current.name),
                        summary = pluralStringResource(
                            R.plurals.debug_adb_tcp_bind_full, AdbTcpPort.MAX_BOUND, AdbTcpPort.MAX_BOUND,
                        ),
                        enabled = false,
                    )
                    else -> ArrowPreference(
                        title = stringResource(R.string.debug_adb_tcp_bind_current, current.name),
                        summary = current.bssid,
                        enabled = canEdit,
                        onClick = { authenticate(current) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            AppSection {
                OverlayDropdownPreference(
                    title = stringResource(R.string.debug_adb_tcp_restart_mode),
                    summary = stringResource(R.string.debug_adb_tcp_restart_summary),
                    items = listOf(
                        stringResource(R.string.debug_adb_tcp_restart_off),
                        stringResource(R.string.debug_adb_tcp_restart_bound),
                        stringResource(R.string.debug_adb_tcp_restart_always),
                    ),
                    selectedIndex = restartMode.ordinal,
                    enabled = canEdit,
                    onSelectedIndexChange = { index ->
                        val selected = AdbTcpPort.RestartMode.entries[index]
                        if (selected == AdbTcpPort.RestartMode.BOUND_WIFI && bound.isEmpty()) {
                            Toast.makeText(context, R.string.debug_adb_tcp_bind_first, Toast.LENGTH_SHORT).show()
                        } else {
                            change { AdbTcpPort.setRestartMode(selected) }
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.debug_adb_tcp_custom),
                    summary = stringResource(R.string.debug_adb_tcp_custom_summary),
                    checked = custom,
                    enabled = canEdit && !listening && !randomOnly,
                    onCheckedChange = {
                        custom = it
                        options.edit { putBoolean("custom", it) }
                    },
                )
                MiuixExpandableContent(visible = custom && !randomOnly) {
                    TextField(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        state = input,
                        label = stringResource(R.string.debug_adb_tcp_port),
                        enabled = canEdit && !listening && !randomOnly,
                        inputTransformation = InputTransformation.byValue { _, proposed ->
                            proposed.filter { it in '0'..'9' }.take(5)
                        },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        }
    }
    val pendingRemoval = removing
    AppWindowDialog(
        show = pendingRemoval != null,
        title = stringResource(R.string.debug_adb_tcp_remove_title),
        summary = pendingRemoval?.let {
            stringResource(R.string.debug_adb_tcp_remove_summary, it.name, it.bssid)
        },
        onDismissRequest = { removing = null },
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.settings_cancel),
                onClick = { removing = null },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.debug_adb_tcp_remove),
                onClick = {
                    removing = null
                    if (pendingRemoval != null) change { AdbTcpPort.removeBinding(pendingRemoval) }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
