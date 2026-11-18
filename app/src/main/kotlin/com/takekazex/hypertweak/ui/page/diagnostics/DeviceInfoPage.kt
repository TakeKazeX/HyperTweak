package com.takekazex.hypertweak.ui.page.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppIconButton as IconButton
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.util.device.BatteryInfoReader
import com.takekazex.hypertweak.util.device.DeviceHardwareInfo
import com.takekazex.hypertweak.util.device.HardwareIdentityReader
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun DeviceInfoPage(onBack: () -> Unit, onBatteryInfo: () -> Unit) {
    val context = LocalContext.current
    var sections by remember(context) { mutableStateOf<List<BatteryInfoReader.Section>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var rootFailed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value == Lifecycle.State.RESUMED
    LaunchedEffect(resumed, revision, context) {
        if (!resumed) return@LaunchedEffect
        loading = true
        rootFailed = false
        sections = withContext(Dispatchers.IO) {
            runCatching { DeviceHardwareInfo.read(context) }
                .onFailure { DebugLog.w("DeviceInfoPage", "hardware snapshot failed", it) }
                .getOrDefault(emptyList())
        }
        val identity = withContext(Dispatchers.IO) {
            HardwareIdentityReader.read { isActive }
        }
        sections = DeviceHardwareInfo.withIdentity(context, sections, identity)
        rootFailed = identity.rootFailed
        loading = false
        awaitCancellation()
    }

    AppScrollPage(
        title = stringResource(R.string.device_info_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.battery_info_back),
        actions = {
                IconButton(onClick = { revision++ }) {
                    Icon(MiuixIcons.Refresh, stringResource(R.string.battery_info_refresh))
                }
            },
    ) {
        AppSection {
            ArrowPreference(
                title = stringResource(R.string.battery_info_menu_title),
                summary = stringResource(R.string.battery_info_menu_summary),
                onClick = onBatteryInfo
            )
        }
        if (loading || sections.isEmpty()) {
            SmallTitle(stringResource(if (loading) R.string.device_info_loading else R.string.battery_unavailable))
        }
        if (rootFailed) {
            AppSection {
                BasicComponent(title = stringResource(R.string.device_info_identity_failed))
            }
        }
        sections.forEach { section ->
            AppSection(title = section.title) {
                Column {
                    section.rows.forEach { row ->
                        // Stacked text retains long values and remains readable with large fonts.
                        BasicComponent(title = row.label, summary = row.value)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
