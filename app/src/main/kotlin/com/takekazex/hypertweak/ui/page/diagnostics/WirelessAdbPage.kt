package com.takekazex.hypertweak.ui.page.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.effect.AppScaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar
import com.takekazex.hypertweak.util.platform.AdbTcpPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun WirelessAdbPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val options = remember(appContext) { AdbTcpPort.options(appContext) }
    var custom by rememberSaveable { mutableStateOf(options.getBoolean("custom", false)) }
    var input by rememberSaveable { mutableStateOf(options.getString("port", "").orEmpty()) }
    var state by remember { mutableStateOf<AdbTcpPort.State?>(null) }
    var working by remember { mutableStateOf(false) }
    var authentication by remember { mutableStateOf<CancellationSignal?>(null) }
    var initialized by remember { mutableStateOf(false) }
    val customPort = input.toIntOrNull()?.takeIf { it in 1..65535 }
    val port = if (custom) customPort ?: -1 else 0
    val bound = state?.bound
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

    suspend fun initialize() {
        working = true
        try {
            initialized = AdbTcpPort.restore(appContext)
            state = if (initialized) AdbTcpPort.read() else null
        } finally {
            working = false
        }
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!initialized) initialize()
            while (true) {
                delay(if (state?.endpoint != null) 1500 else 3500)
                if (initialized && !working) state = AdbTcpPort.read()
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
            state = AdbTcpPort.read()
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
                        if (!signal.isCanceled) change { AdbTcpPort.bind(network, port) }
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

    val addCurrent = current?.takeIf { it != bound }
    val wifiChoices = listOfNotNull(
        stringResource(R.string.debug_adb_tcp_unbound),
        bound?.let { "${it.name} · ${it.bssid}" },
        addCurrent?.let { stringResource(R.string.debug_adb_tcp_bind_current, it.name) },
    )
    AppScaffold(topBar = {
        AppTopAppBar(
            title = stringResource(R.string.debug_adb_tcp_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.debug_back))
                }
            },
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
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
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
                                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                            },
                        )
                    }
                }
            }
            if (!usingSystem) {
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = stringResource(R.string.debug_adb_tcp_manual),
                            summary = stringResource(R.string.debug_adb_tcp_manual_summary),
                            checked = listening,
                            enabled = canEdit && (listening || (current != null && (port >= 0 || randomOnly))),
                            onCheckedChange = { enabled -> change { AdbTcpPort.setEnabled(enabled, current, port) } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    val copyModifier = if (endpoint == null) Modifier else Modifier.combinedClickable(
                            onClick = {},
                            onLongClickLabel = stringResource(R.string.debug_adb_tcp_copy),
                            onLongClick = {
                                scope.launch {
                                    val verified = AdbTcpPort.read()?.endpoint
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
            }
            if (!usingSystem) {
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
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
                                if (selected == AdbTcpPort.RestartMode.BOUND_WIFI && bound == null) {
                                    Toast.makeText(context, R.string.debug_adb_tcp_bind_first, Toast.LENGTH_SHORT).show()
                                } else {
                                    change { AdbTcpPort.setRestartMode(selected) }
                                }
                            },
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.debug_adb_tcp_auto),
                            summary = current?.let {
                                stringResource(R.string.debug_adb_tcp_current, it.name, it.bssid)
                            } ?: stringResource(R.string.debug_adb_tcp_wifi_unavailable),
                            items = wifiChoices,
                            selectedIndex = if (bound == null) 0 else 1,
                            enabled = canEdit && wifiChoices.size > 1,
                            onSelectedIndexChange = { index ->
                                if (index == 0 && bound != null) change { AdbTcpPort.bind(null, port) }
                                if (index == wifiChoices.lastIndex && index > 0 && addCurrent != null) {
                                    authenticate(addCurrent)
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
                                value = input,
                                label = stringResource(R.string.debug_adb_tcp_port),
                                enabled = canEdit && !listening && !randomOnly,
                                onValueChange = {
                                    input = it.filter { digit -> digit in '0'..'9' }.take(5)
                                    options.edit { putString("port", input) }
                                },
                                maxLines = 1,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
