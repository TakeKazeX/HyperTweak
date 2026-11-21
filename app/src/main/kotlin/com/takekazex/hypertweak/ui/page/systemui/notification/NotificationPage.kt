package com.takekazex.hypertweak.ui.page.systemui.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.ui.page.platform.NotificationBannerBatchSection
import com.takekazex.hypertweak.util.platform.PlatformLevel
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun NotificationPage(
    onBack: () -> Unit,
    onNavigateToNotificationHeader: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val settingsStore = LocalAppSettingsStore.current
    var barrageAnyApp by rememberStoredSetting(PlatformSettingsKeys.KEY_BARRAGE_ANY_APP, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_BARRAGE_ANY_APP, false) }
    var notifMoreSettings by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_MORE_SETTINGS, settingsStore::putBoolean) { settingsStore.systemUi.notificationMoreSettings() }
    var notifBadge by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_BADGE, settingsStore::putBoolean) { settingsStore.systemUi.notificationBadge() }
    var notifMiniWindow by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_MINI_WINDOW, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MINI_WINDOW, false) }
    var notifBlockFold by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_BLOCK_FOLD, settingsStore::putBoolean) { settingsStore.systemUi.notificationBlockFold() }
    var iconFilterMode by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_ICON_FILTER_MODE, settingsStore::putInt) { com.takekazex.hypertweak.settings.contract.NotificationIconPolicy.normalize(settingsStore.getInt(SystemUiSettingsKeys.KEY_NOTIFICATION_ICON_FILTER_MODE, 0)) }
    NotificationMenuLayout(stringResource(R.string.settings_system_ui_section_notifications), onBack) {
        MissingScopeCard("com.xiaomi.barrage")
        AppSection(title = stringResource(R.string.settings_system_ui_section_notifications)) {
            SwitchPreference(
                checked = notifMoreSettings,
                onCheckedChange = { value ->
                    notifMoreSettings = value
                },
                title = stringResource(R.string.settings_notification_more_settings_title),
                summary = stringResource(R.string.settings_notification_more_settings_summary)
            )
            SwitchPreference(
                checked = notifBadge,
                onCheckedChange = { value ->
                    notifBadge = value
                },
                title = stringResource(R.string.settings_notification_badge_title),
                summary = stringResource(R.string.settings_notification_badge_summary)
            )
            SwitchPreference(
                checked = notifMiniWindow,
                onCheckedChange = { value ->
                    notifMiniWindow = value
                },
                title = stringResource(R.string.settings_notification_mini_window_title),
                summary = stringResource(R.string.settings_notification_mini_window_summary)
            )
            SwitchPreference(
                checked = notifBlockFold,
                onCheckedChange = { value ->
                    notifBlockFold = value
                },
                title = stringResource(R.string.settings_notification_block_fold_title),
                summary = stringResource(R.string.settings_notification_block_fold_summary)
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.notification_icon_threshold_title),
                summary = stringResource(if (iconFilterMode == 0) R.string.notification_icon_hyperos else R.string.notification_icon_aosp),
                showValue = false,
                items = listOf(stringResource(R.string.notification_icon_hyperos), stringResource(R.string.notification_icon_aosp)),
                selectedIndex = iconFilterMode,
                onSelectedIndexChange = { index ->
                    iconFilterMode = index
                }
            )
        }

        NotificationBannerBatchSection()

        AppSection(title = stringResource(R.string.home_scope_barrage)) {
            SwitchPreference(
                checked = barrageAnyApp,
                onCheckedChange = { enabled ->
                    barrageAnyApp = enabled
                },
                title = stringResource(R.string.settings_barrage_any_app_title),
                summary = stringResource(R.string.settings_barrage_any_app_summary),
            )
        }

        if (PlatformLevel.isOs4) {
            AppSection(title = stringResource(R.string.settings_system_ui_section_notification_shade)) {
                SwitchPreference(
                    checked = appSettings.notificationHeaderClockSeconds,
                    onCheckedChange = { appSettings.notificationHeaderClockSeconds = it },
                    title = stringResource(R.string.settings_notification_header_clock_seconds_title),
                    summary = stringResource(R.string.settings_notification_header_clock_seconds_summary)
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_notification_header_options_title),
                    summary = stringResource(R.string.settings_notification_header_options_summary),
                    onClick = onNavigateToNotificationHeader
                )
                SwitchPreference(
                    checked = appSettings.notificationMonetTextColor,
                    onCheckedChange = { appSettings.notificationMonetTextColor = it },
                    title = stringResource(R.string.settings_notification_monet_text_color_title),
                    summary = stringResource(R.string.settings_notification_monet_text_color_summary)
                )
                SwitchPreference(
                    checked = appSettings.notificationAbsoluteTime,
                    onCheckedChange = { appSettings.notificationAbsoluteTime = it },
                    title = stringResource(R.string.settings_notification_absolute_time_title),
                    summary = stringResource(R.string.settings_notification_absolute_time_summary)
                )
                SwitchPreference(
                    checked = appSettings.notificationFontWeight,
                    onCheckedChange = { appSettings.notificationFontWeight = it },
                    title = stringResource(R.string.settings_notification_font_weight_title),
                    summary = stringResource(R.string.settings_notification_font_weight_summary)
                )
            }
        }

    }
}

@Composable
fun MediaCardPage(
    onBack: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    NotificationMenuLayout(stringResource(R.string.settings_system_ui_section_media), onBack) {
        if (PlatformLevel.isOs4) {
            AppSection(title = stringResource(R.string.settings_system_ui_section_media)) {
                val carouselStyle = appSettings.mediaCardCarouselStyle
                OverlayDropdownPreference(
                    title = stringResource(R.string.settings_media_card_carousel_title),
                    summary = stringResource(R.string.settings_media_card_carousel_summary),
                    items = listOf(
                        stringResource(R.string.settings_media_card_single),
                        stringResource(R.string.settings_media_card_aosp),
                        stringResource(R.string.settings_media_card_hyperos),
                    ),
                    selectedIndex = if (carouselStyle in 0..2) carouselStyle else if (appSettings.mediaCardSwipe) 1 else 0,
                    onSelectedIndexChange = { appSettings.mediaCardCarouselStyle = it }
                )
                // Media cards: remove the source app icon overlaid on the cover corner, in
                // both the notification shade and the island. The render chains are OS4.
                SwitchPreference(
                    checked = appSettings.mediaCardHideAppIcon,
                    onCheckedChange = { appSettings.mediaCardHideAppIcon = it },
                    title = stringResource(R.string.settings_media_hide_app_icon_title),
                    summary = stringResource(R.string.settings_media_hide_app_icon_summary)
                )
                // Media cards: hide the device-switch button (shade + island + plugin main
                // card).
                SwitchPreference(
                    checked = appSettings.mediaCardHideDeviceSwitch,
                    onCheckedChange = { appSettings.mediaCardHideDeviceSwitch = it },
                    title = stringResource(R.string.settings_media_hide_device_switch_title),
                    summary = stringResource(R.string.settings_media_hide_device_switch_summary)
                )
            }
        }

    }
}

@Composable
private fun NotificationMenuLayout(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scroll = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }
    AppScrollPage(
        title = title,
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_system_ui_back),
        scrollState = scroll,
    ) {
        content()
    }
}
