package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Existing hook settings keep their MainActivity state and restart callbacks. */
@Composable
fun PurificationPage(
    onBack: () -> Unit,
    onNavigateToMarket: () -> Unit,
    onNavigateToCommunity: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val settingsStore = LocalAppSettingsStore.current
    var aicrCopyDirectDefaultBrowser by rememberStoredSetting(PlatformSettingsKeys.KEY_AICR_COPY_DIRECT_DEFAULT_BROWSER, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_AICR_COPY_DIRECT_DEFAULT_BROWSER, false) }
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    AppScrollPage(
        title = stringResource(R.string.purification_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_system_ui_back),
        scrollState = scrollState,
    ) {
        MissingScopeCard(
            "com.miui.voiceassist", "com.milink.service",
            "com.xiaomi.finddevice", "com.android.settings", "system",
            "com.android.intentresolver", "com.xiaomi.aicr", "com.xiaomi.trustservice",
            "com.miui.guardprovider", "com.android.phone", "com.xiaomi.phone"
        )
        AppSection {
            Column {
                top.yukonga.miuix.kmp.preference.ArrowPreference(
                    title = stringResource(R.string.market_title),
                    startAction = {
                        com.takekazex.hypertweak.ui.page.home.ApplicationIcon(
                            packageName = "com.xiaomi.market", modifier = Modifier.padding(end = 6.dp),
                            contentDescription = stringResource(R.string.market_title))
                    }, onClick = onNavigateToMarket)
                top.yukonga.miuix.kmp.preference.ArrowPreference(
                    title = stringResource(R.string.community_title),
                    startAction = {
                        com.takekazex.hypertweak.ui.page.home.ApplicationIcon(
                            packageName = "com.xiaomi.vipaccount", modifier = Modifier.padding(end = 6.dp),
                            contentDescription = stringResource(R.string.community_title))
                    }, onClick = onNavigateToCommunity)
            }
        }

        SystemHelperSection()
        AivsDirectorySection()
        DarkModeScheduleSection()
        SharePanelSection()
        AppSection(stringResource(R.string.tweaks_aicr_section)) {
            SwitchPreference(
                checked = aicrCopyDirectDefaultBrowser,
                onCheckedChange = { enabled ->
                    aicrCopyDirectDefaultBrowser = enabled
                },
                title = stringResource(R.string.tweaks_aicr_default_browser_title),
                summary = stringResource(R.string.tweaks_aicr_default_browser_summary)
            )
        }
        AppSection(stringResource(R.string.tweaks_mitrust_title)) {
            SwitchPreference(
                checked = appSettings.disableMiTrustRiskMonitoring,
                onCheckedChange = { appSettings.disableMiTrustRiskMonitoring = it },
                title = stringResource(R.string.tweaks_disable_risk_monitoring_title),
                summary = stringResource(R.string.tweaks_disable_risk_monitoring_summary)
            )
        }


        AppSection(stringResource(R.string.tweaks_guard_provider_title)) {
            SwitchPreference(
                checked = appSettings.disableGuardEnvironmentCheck,
                onCheckedChange = { appSettings.disableGuardEnvironmentCheck = it },
                title = stringResource(R.string.tweaks_disable_environment_check_title),
                summary = stringResource(R.string.tweaks_disable_environment_check_summary)
            )
            SwitchPreference(
                checked = appSettings.blockGuardUploadAppList,
                onCheckedChange = { appSettings.blockGuardUploadAppList = it },
                title = stringResource(R.string.tweaks_block_upload_app_list_title),
                summary = stringResource(R.string.tweaks_block_upload_app_list_summary)
            )
        }


        AppSection(stringResource(R.string.tweaks_milink_title)) {
            SwitchPreference(
                checked = appSettings.blockMiLinkHpplayFiles,
                onCheckedChange = { appSettings.blockMiLinkHpplayFiles = it },
                title = stringResource(R.string.tweaks_block_milink_hpplay_files_title),
                summary = stringResource(R.string.tweaks_block_milink_hpplay_files_summary)
            )
        }


        AppSection(stringResource(R.string.tweaks_phone_title)) {
            SwitchPreference(
                checked = appSettings.disableVideoRingback,
                onCheckedChange = { appSettings.disableVideoRingback = it },
                title = stringResource(R.string.settings_disable_video_ringback_title),
                summary = stringResource(R.string.settings_disable_video_ringback_summary)
            )
        }


        SmartDnsSection()
        WirelessDiagnosticsSection()
    }
}
