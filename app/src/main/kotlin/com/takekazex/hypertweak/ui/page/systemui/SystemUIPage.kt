package com.takekazex.hypertweak.ui.page.systemui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.settings.contract.TileStateMessageDuration
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import com.takekazex.hypertweak.ui.page.systemui.controlcenter.TileStateMessageDurationDialog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * System UI tweaks (Features tab → System UI). This second-level page groups notification fixes,
 * multitasking transitions, lockscreen and display options, media-card switches,
 * control-center sliders, and navigation-bar settings. Existing tracked switches stay hoisted in
 * [MainActivity]; self-contained groups read their saved preferences here and offer local restarts.
 */
@Composable
fun SystemUIPage(
    onBack: () -> Unit,
    onNavigateToChargingDetail: () -> Unit,
    onNavigateToLockscreenBottomText: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToMediaCard: () -> Unit,
    onNavigateToIconTuner: () -> Unit,
    onNavigateToPrivacyIndicator: () -> Unit,
    onNavigateToAodIcons: () -> Unit,
    onNavigateToSliderPercentage: () -> Unit
) {
    val appSettings = LocalAppSettings.current
    val services = LocalAppServices.current
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val upstreamBackGesture = com.takekazex.hypertweak.ui.effect.rememberUpstreamBackGestureInstalled()
    var deviceCenterSingleRow by rememberStoredSetting(SystemUiSettingsKeys.KEY_CC_DEVICE_CENTER_SINGLE_ROW, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_CC_DEVICE_CENTER_SINGLE_ROW, false) }
    var hideLockscreenDate by rememberStoredSetting(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE, false) }

    var tileMessageDialog by rememberSaveable { mutableStateOf(false) }
    var tileMessageSeconds by rememberSaveable { mutableFloatStateOf(TileStateMessageDuration.normalize(settingsStore.getInt(SystemUiSettingsKeys.KEY_CC_TILE_MESSAGE_DURATION_MS, TileStateMessageDuration.DEFAULT_MS)) / 1000f) }

    val tileMessageSlider = rememberSliderEdit(tileMessageSeconds)

    fun setTileMessageSeconds(value: Float) {
        val milliseconds = TileStateMessageDuration.normalize((value * 1000).roundToInt())
        tileMessageSeconds = milliseconds / 1000f
        settingsStore.putInt(SystemUiSettingsKeys.KEY_CC_TILE_MESSAGE_DURATION_MS, milliseconds)
    }

    var freeformBlur by rememberStoredSetting(PlatformSettingsKeys.KEY_FREEFORM_BLUR_TRANSITION, settingsStore::putBoolean) { settingsStore.platform.freeformBlurTransition() }

    val powerButtonActionOptions = remember {
        listOf(
            PlatformSettingsKeys.POWER_BUTTON_ACTION_DISABLED to resources.getString(R.string.tweaks_power_button_action_follow_system),
            PlatformSettingsKeys.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH to resources.getString(R.string.tweaks_action_circle_to_search),
            PlatformSettingsKeys.POWER_BUTTON_ACTION_GOOGLE_LENS to resources.getString(R.string.tweaks_action_google_lens),
            PlatformSettingsKeys.POWER_BUTTON_ACTION_DEFAULT_ASSISTANT to resources.getString(R.string.tweaks_action_default_assistant)
        )
    }

    AppScrollPage(
        title = stringResource(R.string.settings_system_ui),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_system_ui_back),
    ) {
        MissingScopeCard("com.android.systemui", "system", "com.miui.aod", "com.android.settings", "com.lbe.security.miui")

        AppSection(title = stringResource(R.string.settings_system_ui_section_general)) {
            SwitchPreference(
                checked = appSettings.immediateMonetRefresh,
                onCheckedChange = { appSettings.immediateMonetRefresh = it },
                title = stringResource(R.string.settings_immediate_monet_refresh),
                summary = stringResource(R.string.settings_immediate_monet_refresh_summary)
            )
        }

        AppSection(title = stringResource(R.string.notification_media_section)) {
            Column {
                ArrowPreference(
                    title = stringResource(R.string.settings_system_ui_section_notifications),
                    summary = stringResource(R.string.notification_menu_summary),
                    onClick = onNavigateToNotifications
                )
                if (PlatformLevel.isOs4) ArrowPreference(
                    title = stringResource(R.string.settings_system_ui_section_media),
                    summary = stringResource(R.string.media_card_menu_summary),
                    onClick = onNavigateToMediaCard
                )
            }
        }

        AppSection(title = stringResource(R.string.settings_system_ui_section_multitasking)) {
            SwitchPreference(
                checked = freeformBlur,
                onCheckedChange = { value ->
                    freeformBlur = value
                },
                title = stringResource(R.string.settings_freeform_blur_title),
                summary = stringResource(R.string.settings_freeform_blur_summary)
            )
        }

        AppSection(title = stringResource(R.string.settings_system_ui_section_status_bar)) {
            if (PlatformLevel.isOs4) {
                SwitchPreference(
                    checked = appSettings.statusBarClockSeconds,
                    onCheckedChange = { appSettings.statusBarClockSeconds = it },
                    title = stringResource(R.string.settings_status_bar_clock_seconds_title),
                    summary = stringResource(R.string.settings_status_bar_clock_seconds_summary)
                )
            }
            ArrowPreference(
                title = stringResource(R.string.settings_icon_tuner),
                summary = stringResource(R.string.settings_icon_tuner_summary),
                onClick = onNavigateToIconTuner
            )
            ArrowPreference(
                title = stringResource(R.string.settings_privacy_indicator),
                summary = stringResource(R.string.settings_privacy_indicator_summary),
                onClick = onNavigateToPrivacyIndicator
            )
        }

        AppSection(title = stringResource(R.string.settings_system_ui_section_lockscreen)) {
            SwitchPreference(
                checked = appSettings.aodFullscreen,
                onCheckedChange = { appSettings.aodFullscreen = it },
                title = stringResource(R.string.tweaks_aod_fullscreen_title),
                summary = stringResource(R.string.tweaks_aod_fullscreen_summary)
            )
            ArrowPreference(
                title = stringResource(R.string.settings_aod_icons_title),
                summary = stringResource(R.string.settings_aod_icons_summary),
                onClick = onNavigateToAodIcons
            )
            SwitchPreference(
                checked = appSettings.hideFingerprintLockscreen,
                onCheckedChange = { appSettings.hideFingerprintLockscreen = it },
                title = stringResource(R.string.tweaks_hide_fingerprint_lockscreen_title),
                summary = stringResource(R.string.tweaks_hide_fingerprint_lockscreen_summary)
            )
            SwitchPreference(
                checked = appSettings.hideFingerprintAod,
                onCheckedChange = { appSettings.hideFingerprintAod = it },
                title = stringResource(R.string.tweaks_hide_fingerprint_aod_title),
                summary = stringResource(R.string.tweaks_hide_fingerprint_aod_summary)
            )
            SwitchPreference(
                checked = appSettings.hideFingerprintAppAuth,
                onCheckedChange = { appSettings.hideFingerprintAppAuth = it },
                title = stringResource(R.string.tweaks_hide_fingerprint_app_auth_title),
                summary = stringResource(R.string.tweaks_hide_fingerprint_app_auth_summary)
            )
            SwitchPreference(
                checked = appSettings.hideLockscreenStatusBar,
                onCheckedChange = { appSettings.hideLockscreenStatusBar = it },
                title = stringResource(R.string.tweaks_hide_lockscreen_status_bar_title),
                summary = stringResource(R.string.tweaks_hide_lockscreen_status_bar_summary)
            )
            SwitchPreference(
                checked = hideLockscreenDate,
                onCheckedChange = { value ->
                    hideLockscreenDate = value
                },
                title = stringResource(R.string.tweaks_hide_lockscreen_date_title),
                summary = stringResource(R.string.tweaks_hide_lockscreen_date_summary)
            )
            // The notification-stack fingerprint avoidance anchors on the OS4
            // `nsslLockYPosition` combine; OS3's keyguard uses a different container.
            if (PlatformLevel.isOs4) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.settings_lockscreen_fingerprint_avoid),
                    summary = stringResource(R.string.settings_lockscreen_fingerprint_avoid_summary),
                    items = listOf(
                        stringResource(R.string.settings_fingerprint_avoid_default),
                        stringResource(R.string.settings_fingerprint_avoid_none),
                        stringResource(R.string.settings_fingerprint_avoid_always)
                    ),
                    selectedIndex = appSettings.lockscreenFingerprintAvoid.coerceIn(0, 2),
                    onSelectedIndexChange = { appSettings.lockscreenFingerprintAvoid = it }
                )
                // Appends live charging telemetry to the bottom lockscreen indication.
                // The master switch and all options live in the second-level page; the
                // master switch needs a SystemUI restart, offered in-page.
                ArrowPreference(
                    title = stringResource(R.string.settings_charging_detail_options),
                    summary = stringResource(R.string.settings_charging_detail_options_summary),
                    onClick = onNavigateToChargingDetail
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_lockscreen_bottom_text_options),
                    summary = stringResource(R.string.settings_lockscreen_bottom_text_options_summary),
                    onClick = onNavigateToLockscreenBottomText
                )
                // Lockscreen notification gates. The first lifts the canShowOnKeyguard
                // whitelist so every notification can appear on the lockscreen; the second
                // stops the lockscreen from hiding notifications that were already shown
                // after the last unlock.
                SwitchPreference(
                    checked = appSettings.lockscreenAllNotifications,
                    onCheckedChange = { appSettings.lockscreenAllNotifications = it },
                    title = stringResource(R.string.settings_lockscreen_all_notifications_title),
                    summary = stringResource(R.string.settings_lockscreen_all_notifications_summary)
                )
                SwitchPreference(
                    checked = appSettings.lockscreenKeepNotifications,
                    onCheckedChange = { appSettings.lockscreenKeepNotifications = it },
                    title = stringResource(R.string.settings_lockscreen_keep_notifications_title),
                    summary = stringResource(R.string.settings_lockscreen_keep_notifications_summary)
                )
            }
        }

        AppSection(title = stringResource(R.string.tweaks_control_center_title)) {
            ArrowPreference(
                title = stringResource(R.string.cc_tile_message_duration_title),
                summary = stringResource(R.string.cc_tile_message_duration_summary),
                onClick = { tileMessageDialog = true },
                holdDownState = tileMessageDialog,
                endActions = {
                    Text(stringResource(R.string.cc_tile_message_duration_value,
                        java.text.DecimalFormat("0.0").format(tileMessageSlider.value)),
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                },
                bottomAction = {
                    Slider(
                        value = tileMessageSlider.value,
                        onValueChange = {
                            tileMessageSlider.update(TileStateMessageDuration.normalize((it * 1000).roundToInt()) / 1000f)
                        },
                        onValueChangeFinished = { tileMessageSlider.finish()?.let(::setTileMessageSeconds) },
                        valueRange = 0f..5f,
                        steps = 49,
                        showKeyPoints = true,
                        keyPoints = TileStateMessageDuration.SNAP_SECONDS,
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step
                    )
                }
            )
            ArrowPreference(
                title = stringResource(R.string.tweaks_slider_show_percentage_title),
                summary = stringResource(sliderDisplayModeLabels[appSettings.sliderDisplayMode.coerceIn(sliderDisplayModeLabels.indices)]),
                onClick = onNavigateToSliderPercentage
            )
            if (PlatformLevel.isOs4) SwitchPreference(
                checked = deviceCenterSingleRow,
                onCheckedChange = { deviceCenterSingleRow = it },
                title = stringResource(R.string.cc_device_center_single_row_title),
                summary = stringResource(R.string.cc_device_center_single_row_summary)
            )
        }

        TileStateMessageDurationDialog(
            show = tileMessageDialog,
            current = tileMessageSeconds,
            onConfirm = ::setTileMessageSeconds,
            onDismiss = { tileMessageDialog = false }
        )

        AppSection(title = stringResource(R.string.tweaks_navigation_bar_title)) {
            SwitchPreference(
                checked = appSettings.hideGestureBar,
                onCheckedChange = { appSettings.hideGestureBar = it },
                title = stringResource(R.string.tweaks_hide_gesture_bar_title),
                summary = stringResource(R.string.tweaks_hide_gesture_bar_summary)
            )
            SwitchPreference(
                checked = appSettings.gestureBarRaiseLayout && appSettings.hideGestureBar,
                onCheckedChange = { appSettings.gestureBarRaiseLayout = it },
                title = stringResource(R.string.tweaks_raise_layout_title),
                summary = stringResource(R.string.tweaks_raise_layout_summary),
                enabled = appSettings.hideGestureBar
            )
            SwitchPreference(
                checked = appSettings.gestureBarAttachTransition,
                onCheckedChange = { appSettings.gestureBarAttachTransition = it },
                title = stringResource(R.string.tweaks_gesture_bar_attach_transition_title),
                summary = stringResource(R.string.tweaks_gesture_bar_attach_transition_summary),
                enabled = !appSettings.hideGestureBar
            )
            SwitchPreference(
                checked = appSettings.hideGestureBarRecents,
                onCheckedChange = { appSettings.hideGestureBarRecents = it },
                title = stringResource(R.string.tweaks_hide_gesture_bar_recents_title),
                summary = stringResource(R.string.tweaks_hide_gesture_bar_recents_summary),
                enabled = !appSettings.hideGestureBar
            )
            SwitchPreference(
                checked = appSettings.hideGestureBarLauncher,
                onCheckedChange = { appSettings.hideGestureBarLauncher = it },
                title = stringResource(R.string.tweaks_hide_gesture_bar_launcher_title),
                summary = stringResource(R.string.tweaks_hide_gesture_bar_launcher_summary),
                enabled = !appSettings.hideGestureBar
            )
            SwitchPreference(
                checked = appSettings.contextualSearchLongPress,
                onCheckedChange = { checked ->
                    appSettings.contextualSearchLongPress = checked
                    // Preferences delivers the acknowledged switch through the common
                    // live-settings channel. Keep this legacy file as a diagnostic record;
                    // it is not the runtime transport.
                    services.nativeRules.publish(
                        context,
                        settingsStore.launcher.hideRecentsClearButton(),
                        settingsStore.launcher.openedFolderColumns(),
                        checked
                    )
                },
                title = stringResource(R.string.tweaks_cts_long_press_title),
                summary = stringResource(if (upstreamBackGesture == true)
                    R.string.back_gesture_upstream_summary else R.string.tweaks_cts_long_press_summary),
                enabled = upstreamBackGesture == false
            )
            if (PlatformLevel.isOs4) {
                SwitchPreference(
                    checked = appSettings.contextualSearchHaptics,
                    onCheckedChange = { appSettings.contextualSearchHaptics = it },
                    title = stringResource(R.string.tweaks_cts_haptics_title),
                    summary = stringResource(R.string.tweaks_cts_haptics_summary),
                    enabled = upstreamBackGesture == false && appSettings.contextualSearchLongPress
                )
            }
            OverlayDropdownPreference(
                title = stringResource(R.string.tweaks_power_button_action_title),
                summary = stringResource(R.string.tweaks_power_button_action_summary),
                items = powerButtonActionOptions.map { it.second },
                selectedIndex = powerButtonActionOptions.indexOfFirst {
                    it.first == appSettings.powerButtonAction
                }.coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    powerButtonActionOptions.getOrNull(index)?.first?.let(
                        { appSettings.powerButtonAction = it }
                    )
                }
            )
        }
    }
}

private val sliderDisplayModeLabels = listOf(
    R.string.slider_percentage_off, R.string.slider_percentage_cc_only,
    R.string.slider_percentage_popup_only, R.string.slider_percentage_both
)
