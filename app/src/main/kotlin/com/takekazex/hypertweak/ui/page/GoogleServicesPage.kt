package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

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
    showGoogleServicesInSettings: Boolean,
    onShowGoogleServicesInSettingsChange: (Boolean) -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    quickShareEnabled: Boolean,
    onQuickShareEnabledChange: (Boolean) -> Unit,
    fullScreenTranslate: Boolean,
    onFullScreenTranslateChange: (Boolean) -> Unit,
    askAboutScreen: Boolean,
    onAskAboutScreenChange: (Boolean) -> Unit,
    unlockPasskey: Boolean,
    onUnlockPasskeyChange: (Boolean) -> Unit,
    fcmLiveEnabled: Boolean,
    onFcmLiveEnabledChange: (Boolean) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.google_services_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.google_services_back))
                }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

            SmallTitle(stringResource(R.string.google_services_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = showGoogleServicesInSettings,
                        onCheckedChange = onShowGoogleServicesInSettingsChange,
                        title = stringResource(R.string.settings_show_google_services_in_settings),
                        summary = stringResource(R.string.settings_show_google_services_in_settings_summary)
                    )
                    SwitchPreference(
                        checked = removeGms,
                        onCheckedChange = onRemoveGmsChange,
                        title = stringResource(R.string.tweaks_gms_bypass_title),
                        summary = stringResource(R.string.tweaks_gms_bypass_summary)
                    )
                    // Bypassing the GMS China ROM restrictions already removes the CN markers that
                    // gate Quick Share, so the phenotype override is redundant while it is on; grey
                    // the switch out instead of force-unlocking share with it.
                    SwitchPreference(
                        checked = quickShareEnabled,
                        onCheckedChange = onQuickShareEnabledChange,
                        title = stringResource(R.string.tweaks_quick_share_title),
                        summary = stringResource(R.string.tweaks_quick_share_summary),
                        enabled = !removeGms
                    )
                    SwitchPreference(
                        checked = fullScreenTranslate,
                        onCheckedChange = onFullScreenTranslateChange,
                        title = stringResource(R.string.tweaks_full_screen_translate_title),
                        summary = stringResource(R.string.tweaks_full_screen_translate_summary)
                    )
                    SwitchPreference(
                        checked = askAboutScreen,
                        onCheckedChange = onAskAboutScreenChange,
                        title = stringResource(R.string.tweaks_ask_about_screen_title),
                        summary = stringResource(R.string.tweaks_ask_about_screen_summary)
                    )
                    SwitchPreference(
                        checked = unlockPasskey,
                        onCheckedChange = onUnlockPasskeyChange,
                        title = stringResource(R.string.tweaks_passkey_title),
                        summary = stringResource(R.string.tweaks_passkey_summary)
                    )
                    SwitchPreference(
                        checked = fcmLiveEnabled,
                        onCheckedChange = onFcmLiveEnabledChange,
                        title = stringResource(R.string.tweaks_fcm_live_title),
                        summary = stringResource(R.string.tweaks_fcm_live_summary)
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
