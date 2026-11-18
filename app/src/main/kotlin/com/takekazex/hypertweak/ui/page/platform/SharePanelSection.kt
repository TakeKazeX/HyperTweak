package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppSection
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SharePanelSection() {
    val settingsStore = LocalAppSettingsStore.current
    var enabled by remember { mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL, false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) {
            enabled = settingsStore.getBoolean(PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL, false)
        }
    }
    AppSection(stringResource(R.string.share_panel_title)) {
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
