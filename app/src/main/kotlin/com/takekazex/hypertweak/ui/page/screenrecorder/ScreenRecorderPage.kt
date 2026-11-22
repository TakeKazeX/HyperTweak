package com.takekazex.hypertweak.ui.page.screenrecorder

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.ScreenRecorderSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun ScreenRecorderPage(onBack: () -> Unit) {
    val store = LocalAppSettingsStore.current
    var saveToMovies by rememberStoredSetting(ScreenRecorderSettingsKeys.KEY_SAVE_TO_MOVIES, store::putBoolean) {
        store.getBoolean(ScreenRecorderSettingsKeys.KEY_SAVE_TO_MOVIES, false)
    }
    var moreFrameRates by rememberStoredSetting(ScreenRecorderSettingsKeys.KEY_MORE_FRAME_RATES, store::putBoolean) {
        store.getBoolean(ScreenRecorderSettingsKeys.KEY_MORE_FRAME_RATES, false)
    }
    AppScrollPage(
        title = stringResource(R.string.screen_recorder_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.icon_back),
    ) {
        MissingScopeCard(ScreenRecorderSettingsKeys.PACKAGE)
        AppSection {
            Column {
                SwitchPreference(
                    checked = saveToMovies,
                    onCheckedChange = { saveToMovies = it },
                    title = stringResource(R.string.screen_recorder_directory_title),
                    summary = stringResource(R.string.screen_recorder_directory_summary),
                )
                SwitchPreference(
                    checked = moreFrameRates,
                    onCheckedChange = { moreFrameRates = it },
                    title = stringResource(R.string.screen_recorder_frame_rates_title),
                    summary = stringResource(R.string.screen_recorder_frame_rates_summary),
                )
            }
        }
    }
}
