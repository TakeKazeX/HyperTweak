package com.takekazex.hypertweak.ui.page

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
import com.takekazex.hypertweak.util.PlatformLevel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * System UI tweaks (Settings → System UI). Second-level page consolidating the SystemUI-scoped
 * options that used to live under Experimental: wallpaper-color refresh, lockscreen fingerprint
 * avoidance, lockscreen charging detail, the lockscreen notification gates and the media-card
 * switches. State stays hoisted in [MainActivity] like it was on the main screen, so toggles
 * still flow through `markTweaked` and the standard "Restart Scoped Apps" dialog.
 */
@Composable
fun SystemUIPage(
    onBack: () -> Unit,
    immediateMonetRefresh: Boolean,
    onImmediateMonetRefreshChange: (Boolean) -> Unit,
    lockscreenFingerprintAvoid: Int,
    onLockscreenFingerprintAvoidChange: (Int) -> Unit,
    onNavigateToChargingDetail: () -> Unit,
    lockscreenAllNotifications: Boolean,
    onLockscreenAllNotificationsChange: (Boolean) -> Unit,
    lockscreenKeepNotifications: Boolean,
    onLockscreenKeepNotificationsChange: (Boolean) -> Unit,
    mediaCardHideAppIcon: Boolean,
    onMediaCardHideAppIconChange: (Boolean) -> Unit,
    mediaCardHideDeviceSwitch: Boolean,
    onMediaCardHideDeviceSwitchChange: (Boolean) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.settings_system_ui),
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
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

            SmallTitle(stringResource(R.string.settings_system_ui_section_general))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = immediateMonetRefresh,
                        onCheckedChange = onImmediateMonetRefreshChange,
                        title = stringResource(R.string.settings_immediate_monet_refresh),
                        summary = stringResource(R.string.settings_immediate_monet_refresh_summary)
                    )
                }
            }

            // The notification-stack fingerprint avoidance anchors on the OS4 `nsslLockYPosition`
            // combine; OS3's keyguard uses a different container.
            if (PlatformLevel.isOs4) {
                SmallTitle(stringResource(R.string.settings_system_ui_section_lockscreen))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_lockscreen_fingerprint_avoid),
                            summary = stringResource(R.string.settings_lockscreen_fingerprint_avoid_summary),
                            items = listOf(
                                stringResource(R.string.settings_fingerprint_avoid_default),
                                stringResource(R.string.settings_fingerprint_avoid_none),
                                stringResource(R.string.settings_fingerprint_avoid_always)
                            ),
                            selectedIndex = lockscreenFingerprintAvoid.coerceIn(0, 2),
                            onSelectedIndexChange = onLockscreenFingerprintAvoidChange
                        )
                        // Appends live charging telemetry to the bottom lockscreen indication.
                        // The master switch and all options live in the second-level page; the
                        // master switch needs a SystemUI restart, offered in-page.
                        ArrowPreference(
                            title = stringResource(R.string.settings_charging_detail_options),
                            summary = stringResource(R.string.settings_charging_detail_options_summary),
                            onClick = onNavigateToChargingDetail
                        )
                        // Lockscreen notification gates. The first lifts the canShowOnKeyguard
                        // whitelist so every notification can appear on the lockscreen; the
                        // second stops the lockscreen from hiding notifications that were already
                        // shown after the last unlock.
                        SwitchPreference(
                            checked = lockscreenAllNotifications,
                            onCheckedChange = onLockscreenAllNotificationsChange,
                            title = stringResource(R.string.settings_lockscreen_all_notifications_title),
                            summary = stringResource(R.string.settings_lockscreen_all_notifications_summary)
                        )
                        SwitchPreference(
                            checked = lockscreenKeepNotifications,
                            onCheckedChange = onLockscreenKeepNotificationsChange,
                            title = stringResource(R.string.settings_lockscreen_keep_notifications_title),
                            summary = stringResource(R.string.settings_lockscreen_keep_notifications_summary)
                        )
                    }
                }
            }

            if (PlatformLevel.isOs4) {
                SmallTitle(stringResource(R.string.settings_system_ui_section_media))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        // Media cards: remove the source app icon overlaid on the cover corner, in
                        // both the notification shade and the island. The render chains are OS4.
                        SwitchPreference(
                            checked = mediaCardHideAppIcon,
                            onCheckedChange = onMediaCardHideAppIconChange,
                            title = stringResource(R.string.settings_media_hide_app_icon_title),
                            summary = stringResource(R.string.settings_media_hide_app_icon_summary)
                        )
                        // Media cards: hide the device-switch button (shade + island + plugin main
                        // card).
                        SwitchPreference(
                            checked = mediaCardHideDeviceSwitch,
                            onCheckedChange = onMediaCardHideDeviceSwitchChange,
                            title = stringResource(R.string.settings_media_hide_device_switch_title),
                            summary = stringResource(R.string.settings_media_hide_device_switch_summary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
