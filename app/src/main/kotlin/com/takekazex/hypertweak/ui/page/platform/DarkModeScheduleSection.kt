package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppSection
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun DarkModeScheduleSection(refreshActive: Boolean) {
    val settingsStore = LocalAppSettingsStore.current
    var enabled by remember { mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_DARK_MODE_IMMEDIATE_SCHEDULE, false)) }
    LaunchedEffect(refreshActive) {
        if (refreshActive) {
            enabled = settingsStore.getBoolean(PlatformSettingsKeys.KEY_DARK_MODE_IMMEDIATE_SCHEDULE, false)
        }
    }
    AppSection(title = stringResource(R.string.dark_mode_schedule_section)) {
        SwitchPreference(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                settingsStore.putBoolean(PlatformSettingsKeys.KEY_DARK_MODE_IMMEDIATE_SCHEDULE, it)
            },
            title = stringResource(R.string.dark_mode_schedule_immediate),
            summary = stringResource(R.string.dark_mode_schedule_immediate_summary)
        )
    }
}
