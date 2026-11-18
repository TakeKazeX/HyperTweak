package com.takekazex.hypertweak.ui.page.platform

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Google-related services (Features tab → System components → Google Services).
 *
 * Second-level page that keeps this group off the already long Features page. Every switch here
 * belongs to a Google component (GMS, the Google app, or the Settings Google-services entry), so the
 * page carries the `com.google.android.gms` icon in the entry row and stays scoped to Google state.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun GoogleServicesPage(
    onBack: () -> Unit,
) {
    val appSettings = LocalAppSettings.current

    AppScrollPage(
        title = stringResource(R.string.google_services_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.google_services_back),
    ) {
        MissingScopeCard(
            "com.android.settings", "com.google.android.googlequicksearchbox",
            "system", "com.miui.securitycenter", "com.xiaomi.scanner", "com.miui.powerkeeper"
        )

        AppSection(stringResource(R.string.google_services_title)) {
            SwitchPreference(
                checked = appSettings.showGoogleServicesInSettings,
                onCheckedChange = { appSettings.showGoogleServicesInSettings = it },
                title = stringResource(R.string.settings_show_google_services_in_settings),
                summary = stringResource(R.string.settings_show_google_services_in_settings_summary)
            )
            SwitchPreference(
                checked = appSettings.removeGms,
                onCheckedChange = { appSettings.removeGms = it },
                title = stringResource(R.string.tweaks_gms_bypass_title),
                summary = stringResource(R.string.tweaks_gms_bypass_summary)
            )
            SwitchPreference(
                checked = appSettings.fullScreenTranslate,
                onCheckedChange = { appSettings.fullScreenTranslate = it },
                title = stringResource(R.string.tweaks_full_screen_translate_title),
                summary = stringResource(R.string.tweaks_full_screen_translate_summary)
            )
            SwitchPreference(
                checked = appSettings.askAboutScreen,
                onCheckedChange = { appSettings.askAboutScreen = it },
                title = stringResource(R.string.tweaks_ask_about_screen_title),
                summary = stringResource(R.string.tweaks_ask_about_screen_summary)
            )
            SwitchPreference(
                checked = appSettings.unlockPasskey,
                onCheckedChange = { appSettings.unlockPasskey = it },
                title = stringResource(R.string.tweaks_passkey_title),
                summary = stringResource(R.string.tweaks_passkey_summary)
            )
            SwitchPreference(
                checked = appSettings.fcmLiveEnabled,
                onCheckedChange = { appSettings.fcmLiveEnabled = it },
                title = stringResource(R.string.tweaks_fcm_live_title),
                summary = stringResource(R.string.tweaks_fcm_live_summary)
            )
        }
    }
}
