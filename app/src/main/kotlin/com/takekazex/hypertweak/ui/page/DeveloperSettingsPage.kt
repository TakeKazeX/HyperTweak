package com.takekazex.hypertweak.ui.page

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.DeveloperSettings
import kotlinx.coroutines.launch
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

@Composable
fun DeveloperSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var showRefreshRate by remember { mutableStateOf(false) }
    var showHdrSdrRatio by remember { mutableStateOf(false) }
    var showTouches by remember { mutableStateOf(false) }
    var pointerLocation by remember { mutableStateOf(false) }
    var usbDebugging by remember { mutableStateOf(false) }

    fun reload() {
        showRefreshRate = DeveloperSettings.refreshRateEnabled() ?: false
        showHdrSdrRatio = DeveloperSettings.hdrSdrRatioEnabled() ?: false
        showTouches = DeveloperSettings.readSystemBoolean(context, DeveloperSettings.SHOW_TOUCHES)
        pointerLocation = DeveloperSettings.readSystemBoolean(context, DeveloperSettings.POINTER_LOCATION)
        usbDebugging = DeveloperSettings.readGlobalBoolean(context, Settings.Global.ADB_ENABLED)
    }

    fun update(value: Boolean, write: suspend () -> Boolean, onSaved: (Boolean) -> Unit) {
        onSaved(value)
        scope.launch {
            if (!write()) {
                onSaved(!value)
                Toast.makeText(context, R.string.developer_settings_write_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    remember(lifecycleOwner) {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
    }.also { observer ->
        DisposableEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.developer_settings_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, stringResource(R.string.developer_settings_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            // Entry point for the full system developer options. The switches below cover the
            // handful of toggles worth surfacing directly; anything else lives in the system page.
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.developer_settings_system_options_title),
                    summary = stringResource(R.string.developer_settings_system_options_summary),
                    onClick = { openSystemDeveloperOptions(context) }
                )
            }
            SmallTitle(stringResource(R.string.developer_settings_display_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = showRefreshRate,
                        onCheckedChange = { value ->
                            update(value, {
                                DeveloperSettings.writeSurfaceFlingerBoolean(
                                    DeveloperSettings.SHOW_REFRESH_RATE_TRANSACTION,
                                    value
                                )
                            }) {
                                showRefreshRate = it
                            }
                        },
                        title = stringResource(R.string.developer_settings_show_refresh_rate),
                        summary = stringResource(R.string.developer_settings_show_refresh_rate_summary)
                    )
                    SwitchPreference(
                        checked = showHdrSdrRatio,
                        onCheckedChange = { value ->
                            update(value, {
                                DeveloperSettings.writeSurfaceFlingerBoolean(
                                    DeveloperSettings.SHOW_HDR_SDR_RATIO_TRANSACTION,
                                    value
                                )
                            }) {
                                showHdrSdrRatio = it
                            }
                        },
                        title = stringResource(R.string.developer_settings_show_hdr_sdr_ratio),
                        summary = stringResource(R.string.developer_settings_show_hdr_sdr_ratio_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.developer_settings_input_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = showTouches,
                        onCheckedChange = { value ->
                            update(value, {
                                DeveloperSettings.writeSystemBoolean(context, DeveloperSettings.SHOW_TOUCHES, value)
                            }) { showTouches = it }
                        },
                        title = stringResource(R.string.developer_settings_show_touches),
                        summary = stringResource(R.string.developer_settings_show_touches_summary)
                    )
                    SwitchPreference(
                        checked = pointerLocation,
                        onCheckedChange = { value ->
                            update(value, {
                                DeveloperSettings.writeSystemBoolean(context, DeveloperSettings.POINTER_LOCATION, value)
                            }) { pointerLocation = it }
                        },
                        title = stringResource(R.string.developer_settings_pointer_location),
                        summary = stringResource(R.string.developer_settings_pointer_location_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.developer_settings_networking_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.developer_settings_mobile_data_title),
                        summary = stringResource(R.string.developer_settings_mobile_data_summary),
                        onClick = { openCellularNetworkDebug(context) }
                    )
                    SwitchPreference(
                        checked = usbDebugging,
                        onCheckedChange = { value ->
                            update(value, {
                                DeveloperSettings.writeGlobalBoolean(context, Settings.Global.ADB_ENABLED, value)
                            }) { usbDebugging = it }
                        },
                        title = stringResource(R.string.developer_settings_usb_debugging),
                        summary = stringResource(R.string.developer_settings_usb_debugging_summary)
                    )
                    ArrowPreference(
                        title = stringResource(R.string.developer_settings_wireless_debugging),
                        summary = stringResource(R.string.developer_settings_wireless_debugging_summary),
                        onClick = { openWirelessDebugging(context) }
                    )
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

/** Opens the full system developer options page (开发者选项). */
private fun openSystemDeveloperOptions(context: Context) {
    try {
        context.startActivity(
            Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, R.string.developer_settings_open_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun openWirelessDebugging(context: Context) {
    val wirelessDebugging = Intent().apply {
        setClassName("com.android.settings", "com.android.settings.SubSettings")
        putExtra(":settings:show_fragment", "com.android.settings.development.AdbWirelessDebuggingFragment")
        putExtra(":settings:show_fragment_as_subsetting", true)
    }
    val intents = listOf(
        wirelessDebugging,
        Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
            // Try the full developer options page on ROMs without the dedicated destination.
        }
    }
    Toast.makeText(context, R.string.developer_settings_open_failed, Toast.LENGTH_SHORT).show()
}

private fun openCellularNetworkDebug(context: Context) {
    val cellularNetwork = Intent("miui.intent.action.CellularNetworkActivity").apply {
        setClassName("com.xiaomi.phone", "com.xiaomi.phone.settings.development.CellularNetworkActivity")
    }
    try {
        context.startActivity(cellularNetwork.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(context, R.string.developer_settings_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
