package com.takekazex.hypertweak.ui.page.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.util.platform.PlatformLevel
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Screen timeout (Settings) and refresh-rate list (MiSettings) switches. Both apply on the page's next open. */
@Composable
fun DisplayControls() {
    if (!PlatformLevel.isOs4) return
    val settingsStore = LocalAppSettingsStore.current
    var neverTimeout by rememberStoredSetting(PlatformSettingsKeys.KEY_ALLOW_NEVER_SCREEN_TIMEOUT, settingsStore::putBoolean) {
        settingsStore.getBoolean(PlatformSettingsKeys.KEY_ALLOW_NEVER_SCREEN_TIMEOUT, false)
    }
    var moreRefreshRates by rememberStoredSetting(MiSettingsSettingsKeys.KEY_MORE_REFRESH_RATES, settingsStore::putBoolean) {
        settingsStore.getBoolean(MiSettingsSettingsKeys.KEY_MORE_REFRESH_RATES, false)
    }

    MissingScopeCard(MiSettingsSettingsKeys.PACKAGE)
    AppSection(title = stringResource(R.string.display_section_title)) {
        SwitchPreference(
            checked = neverTimeout,
            onCheckedChange = { neverTimeout = it },
            title = stringResource(R.string.display_never_screen_timeout_title),
            summary = stringResource(R.string.display_never_screen_timeout_summary)
        )
        SwitchPreference(
            checked = moreRefreshRates,
            onCheckedChange = { moreRefreshRates = it },
            title = stringResource(R.string.display_more_refresh_rates_title),
            summary = stringResource(R.string.display_more_refresh_rates_summary)
        )
    }
}
