package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.util.platform.PlatformLevel
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Google-related services (Features tab → System components → Google Services).
 *
 * Includes system integrations for Google features, such as the SystemUI Motion Cues overlay.
 */
@Composable
fun GoogleServicesPage(
    onBack: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val settingsStore = LocalAppSettingsStore.current
    var motionCuesFreeformFix by rememberStoredSetting(
        PlatformSettingsKeys.KEY_MOTION_CUES_FREEFORM_FIX,
        settingsStore::putBoolean,
    ) { settingsStore.platform.motionCuesFreeformFix() }

    AppScrollPage(
        title = stringResource(R.string.google_services_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.google_services_back),
    ) {
        MissingScopeCard(
            "com.android.settings", "com.google.android.googlequicksearchbox",
            "system", "com.miui.securitycenter", "com.xiaomi.scanner", "com.miui.powerkeeper"
        )

        if (PlatformLevel.isOs4) MissingScopeCard("com.android.systemui")

        AppSection(title = stringResource(R.string.google_services_title)) {
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
            if (PlatformLevel.isOs4) {
                SwitchPreference(
                    checked = motionCuesFreeformFix,
                    onCheckedChange = { motionCuesFreeformFix = it },
                    title = stringResource(R.string.settings_motion_cues_freeform_title),
                    summary = stringResource(R.string.settings_motion_cues_freeform_summary),
                )
            }
        }
    }
}
