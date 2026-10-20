package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar

/** Existing hook settings keep their MainActivity state and restart callbacks. */
@Composable
fun PurificationPage(
    onBack: () -> Unit,
    disableVideoRingback: Boolean,
    onDisableVideoRingbackChange: (Boolean) -> Unit,
    disableMiTrustRiskMonitoring: Boolean,
    onDisableMiTrustRiskMonitoringChange: (Boolean) -> Unit,
    disableGuardEnvironmentCheck: Boolean,
    onDisableGuardEnvironmentCheckChange: (Boolean) -> Unit,
    blockGuardUploadAppList: Boolean,
    onBlockGuardUploadAppListChange: (Boolean) -> Unit,
    blockMiLinkHpplayFiles: Boolean,
    onBlockMiLinkHpplayFilesChange: (Boolean) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.purification_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.settings_system_ui_back))
                }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            SmallTitle(text = stringResource(R.string.tweaks_mitrust_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = disableMiTrustRiskMonitoring,
                    onCheckedChange = onDisableMiTrustRiskMonitoringChange,
                    title = stringResource(R.string.tweaks_disable_risk_monitoring_title),
                    summary = stringResource(R.string.tweaks_disable_risk_monitoring_summary)
                )
            }


            SmallTitle(text = stringResource(R.string.tweaks_guard_provider_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = disableGuardEnvironmentCheck,
                        onCheckedChange = onDisableGuardEnvironmentCheckChange,
                        title = stringResource(R.string.tweaks_disable_environment_check_title),
                        summary = stringResource(R.string.tweaks_disable_environment_check_summary)
                    )
                    SwitchPreference(
                        checked = blockGuardUploadAppList,
                        onCheckedChange = onBlockGuardUploadAppListChange,
                        title = stringResource(R.string.tweaks_block_upload_app_list_title),
                        summary = stringResource(R.string.tweaks_block_upload_app_list_summary)
                    )
                }
            }


            SmallTitle(text = stringResource(R.string.tweaks_milink_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = blockMiLinkHpplayFiles,
                    onCheckedChange = onBlockMiLinkHpplayFilesChange,
                    title = stringResource(R.string.tweaks_block_milink_hpplay_files_title),
                    summary = stringResource(R.string.tweaks_block_milink_hpplay_files_summary)
                )
            }


            SmallTitle(text = stringResource(R.string.tweaks_phone_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = disableVideoRingback,
                    onCheckedChange = onDisableVideoRingbackChange,
                    title = stringResource(R.string.settings_disable_video_ringback_title),
                    summary = stringResource(R.string.settings_disable_video_ringback_summary)
                )
            }


            WirelessDiagnosticsSection()
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
