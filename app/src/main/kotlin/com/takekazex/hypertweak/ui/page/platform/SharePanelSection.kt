package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppSection
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SharePanelSection(refreshActive: Boolean) {
    val settingsStore = LocalAppSettingsStore.current
    var enabled by remember { mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL, false)) }
    LaunchedEffect(refreshActive) {
        if (refreshActive) {
            enabled = settingsStore.getBoolean(PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL, false)
        }
    }
    AppSection(title = stringResource(R.string.share_panel_title)) {
        SwitchPreference(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                settingsStore.putBoolean(PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL, it)
            },
            title = stringResource(R.string.share_panel_show_all),
            summary = stringResource(R.string.share_panel_show_all_summary)
        )
    }
}
