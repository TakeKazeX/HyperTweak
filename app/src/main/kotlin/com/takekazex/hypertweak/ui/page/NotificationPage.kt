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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.PlatformLevel
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun NotificationPage(
    onBack: () -> Unit,
    notificationHeaderClockSeconds: Boolean,
    onNotificationHeaderClockSecondsChange: (Boolean) -> Unit,
    notificationMonetTextColor: Boolean,
    onNotificationMonetTextColorChange: (Boolean) -> Unit,
    notificationFontWeight: Boolean,
    onNotificationFontWeightChange: (Boolean) -> Unit,
    notificationAbsoluteTime: Boolean,
    onNotificationAbsoluteTimeChange: (Boolean) -> Unit,
    onNavigateToNotificationHeader: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current
    var notifMoreSettings by remember { mutableStateOf(Preferences.notificationMoreSettings()) }
    var notifBadge by remember { mutableStateOf(Preferences.notificationBadge()) }
    var notifMiniWindow by rememberSaveable { mutableStateOf(Preferences.getBoolean(Preferences.KEY_NOTIFICATION_MINI_WINDOW, false)) }
    var notifBlockFold by remember { mutableStateOf(Preferences.notificationBlockFold()) }
    var notifSettingsRestartPending by rememberSaveable { mutableStateOf(false) }
    var notifSystemUiRestartPending by rememberSaveable { mutableStateOf(false) }
    var iconFilterMode by rememberSaveable { mutableIntStateOf(com.takekazex.hypertweak.hook.rules.systemui.NotificationIconPolicy.normalize(Preferences.getInt(Preferences.KEY_NOTIFICATION_ICON_FILTER_MODE, 0))) }
    NotificationMenuLayout(stringResource(R.string.settings_system_ui_section_notifications), onBack) {
        SmallTitle(stringResource(R.string.settings_system_ui_section_notifications))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = notifMoreSettings,
                    onCheckedChange = { value ->
                        notifMoreSettings = value
                        Preferences.putBoolean(Preferences.KEY_NOTIFICATION_MORE_SETTINGS, value)
                        Preferences.flush()
                        notifSettingsRestartPending = true
                        notifSystemUiRestartPending = true
                        requestRestartScopes(
                            RestartScopeSelection(settings = true, systemUi = true)
                        )
                    },
                    title = stringResource(R.string.settings_notification_more_settings_title),
                    summary = stringResource(R.string.settings_notification_more_settings_summary)
                )
                SwitchPreference(
                    checked = notifBadge,
                    onCheckedChange = { value ->
                        notifBadge = value
                        Preferences.putBoolean(Preferences.KEY_NOTIFICATION_BADGE, value)
                        Preferences.flush()
                        notifSettingsRestartPending = true
                        requestRestartScopes(RestartScopeSelection(settings = true))
                    },
                    title = stringResource(R.string.settings_notification_badge_title),
                    summary = stringResource(R.string.settings_notification_badge_summary)
                )
                SwitchPreference(
                    checked = notifMiniWindow,
                    onCheckedChange = { value ->
                        notifMiniWindow = value
                        Preferences.putBoolean(Preferences.KEY_NOTIFICATION_MINI_WINDOW, value)
                        Preferences.flush()
                    },
                    title = stringResource(R.string.settings_notification_mini_window_title),
                    summary = stringResource(R.string.settings_notification_mini_window_summary)
                )
                SwitchPreference(
                    checked = notifBlockFold,
                    onCheckedChange = { value ->
                        notifBlockFold = value
                        Preferences.putBoolean(Preferences.KEY_NOTIFICATION_BLOCK_FOLD, value)
                        Preferences.flush()
                        notifSystemUiRestartPending = true
                        requestRestartScopes(RestartScopeSelection(systemUi = true))
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
                        Preferences.putInt(Preferences.KEY_NOTIFICATION_ICON_FILTER_MODE, iconFilterMode)
                        Preferences.flush()
                    }
                )
                if (notifSettingsRestartPending) {
                    val restartSelection = RestartScopeSelection(settings = true)
                    ArrowPreference(
                        title = stringResource(R.string.settings_notification_restart_settings_title),
                        summary = stringResource(R.string.settings_notification_restart_settings_summary),
                        enabled = !restartInProgress(restartSelection),
                        onClick = {
                            Preferences.flush()
                            RestartUtils.restartScope(
                                context = context,
                                coroutineScope = coroutineScope,
                                selection = restartSelection,
                                onCompleted = { completed ->
                                    handleRestartedScopes(completed)
                                    if (completed.settings) notifSettingsRestartPending = false
                                },
                            )
                        }
                    )
                }
                if (notifSystemUiRestartPending) {
                    val restartSelection = RestartScopeSelection(systemUi = true)
                    ArrowPreference(
                        title = stringResource(R.string.aosp_restart_system_ui),
                        summary = stringResource(R.string.aosp_restart_system_ui_summary),
                        enabled = !restartInProgress(restartSelection),
                        onClick = {
                            Preferences.flush()
                            RestartUtils.restartScope(
                                context = context,
                                coroutineScope = coroutineScope,
                                selection = restartSelection,
                                onCompleted = { completed ->
                                    handleRestartedScopes(completed)
                                    if (completed.systemUi) notifSystemUiRestartPending = false
                                },
                            )
                        }
                    )
                }
            }
        }

        NotificationBannerBatchSection()

        if (PlatformLevel.isOs4) {
            SmallTitle(stringResource(R.string.settings_system_ui_section_notification_shade))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = notificationHeaderClockSeconds,
                        onCheckedChange = onNotificationHeaderClockSecondsChange,
                        title = stringResource(R.string.settings_notification_header_clock_seconds_title),
                        summary = stringResource(R.string.settings_notification_header_clock_seconds_summary)
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_notification_header_options_title),
                        summary = stringResource(R.string.settings_notification_header_options_summary),
                        onClick = onNavigateToNotificationHeader
                    )
                    SwitchPreference(
                        checked = notificationMonetTextColor,
                        onCheckedChange = onNotificationMonetTextColorChange,
                        title = stringResource(R.string.settings_notification_monet_text_color_title),
                        summary = stringResource(R.string.settings_notification_monet_text_color_summary)
                    )
                    SwitchPreference(
                        checked = notificationAbsoluteTime,
                        onCheckedChange = onNotificationAbsoluteTimeChange,
                        title = stringResource(R.string.settings_notification_absolute_time_title),
                        summary = stringResource(R.string.settings_notification_absolute_time_summary)
                    )
                    SwitchPreference(
                        checked = notificationFontWeight,
                        onCheckedChange = onNotificationFontWeightChange,
                        title = stringResource(R.string.settings_notification_font_weight_title),
                        summary = stringResource(R.string.settings_notification_font_weight_summary)
                    )
                }
            }
        }

    }
}

@Composable
fun MediaCardPage(
    onBack: () -> Unit,
    mediaCardHideAppIcon: Boolean,
    onMediaCardHideAppIconChange: (Boolean) -> Unit,
    mediaCardHideDeviceSwitch: Boolean,
    onMediaCardHideDeviceSwitchChange: (Boolean) -> Unit,
) {
    NotificationMenuLayout(stringResource(R.string.settings_system_ui_section_media), onBack) {
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

    }
}

@Composable
private fun NotificationMenuLayout(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val scroll = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }
    Scaffold(topBar = {
        TopAppBar(title = title, scrollBehavior = scrollBehavior, navigationIcon = {
            IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.settings_system_ui_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(scroll)) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            content()
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
