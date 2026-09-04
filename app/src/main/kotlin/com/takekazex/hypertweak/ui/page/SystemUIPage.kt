package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarAction
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
 * System UI tweaks (Features tab → System UI). Second-level page consolidating the SystemUI-scoped
 * options: wallpaper-color refresh, lockscreen & display (AOD fullscreen, fingerprint icon,
 * lockscreen status bar), lockscreen fingerprint avoidance, charging detail, the lockscreen
 * notification gates, the media-card switches, the control-center sliders and the navigation-bar
 * gesture/power-button settings. State stays hoisted in [MainActivity], so toggles still flow
 * through `markTweaked` and the standard "Restart Scoped Apps" dialog.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun SystemUIPage(
    onBack: () -> Unit,
    immediateMonetRefresh: Boolean,
    onImmediateMonetRefreshChange: (Boolean) -> Unit,
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    hideFingerprint: Boolean,
    onHideFingerprintChange: (Boolean) -> Unit,
    hideLockscreenStatusBar: Boolean,
    onHideLockscreenStatusBarChange: (Boolean) -> Unit,
    notificationHeaderClockSeconds: Boolean,
    onNotificationHeaderClockSecondsChange: (Boolean) -> Unit,
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
    onMediaCardHideDeviceSwitchChange: (Boolean) -> Unit,
    sliderShowPercentage: Boolean,
    onSliderShowPercentageChange: (Boolean) -> Unit,
    sliderSamePercentageStyle: Boolean,
    onSliderSamePercentageChange: (Boolean) -> Unit,
    hideGestureBar: Boolean,
    onHideGestureBarChange: (Boolean) -> Unit,
    gestureBarRaiseLayout: Boolean,
    onGestureBarRaiseLayoutChange: (Boolean) -> Unit,
    gestureBarActionsEnabled: Boolean,
    onGestureBarActionsEnabledChange: (Boolean) -> Unit,
    powerButtonAction: Int,
    onPowerButtonActionChange: (Int) -> Unit,
    powerButtonHaptic: Boolean,
    onPowerButtonHapticChange: (Boolean) -> Unit,
    gestureBarLongPressAction: Int,
    onGestureBarLongPressActionChange: (Int) -> Unit,
    gestureBarDoubleTapAction: Int,
    onGestureBarDoubleTapActionChange: (Int) -> Unit,
    miuiBackGestureHook: Boolean,
    onMiuiBackGestureHookChange: (Boolean) -> Unit,
    crossTaskWallpaperBackground: Boolean,
    onCrossTaskWallpaperBackgroundChange: (Boolean) -> Unit,
    aospBackIndicator: Boolean,
    onAospBackIndicatorChange: (Boolean) -> Unit,
    aospBackHaptics: Boolean,
    onAospBackHapticsChange: (Boolean) -> Unit,
    aospBackHapticsEnhanced: Boolean,
    onAospBackHapticsEnhancedChange: (Boolean) -> Unit,
    aospBackSlideAnimation: Boolean,
    onAospBackSlideAnimationChange: (Boolean) -> Unit,
    launcherSupportsBackRoute: Boolean
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val gestureActionOptions = remember {
        listOf(
            GestureBarAction.DISABLED to context.getString(R.string.tweaks_action_disabled),
            GestureBarAction.DEFAULT_ASSISTANT to context.getString(R.string.tweaks_action_default_assistant),
            GestureBarAction.CIRCLE_TO_SEARCH to context.getString(R.string.tweaks_action_circle_to_search)
        )
    }
    val powerButtonActionOptions = remember {
        listOf(
            Preferences.POWER_BUTTON_ACTION_DISABLED to context.getString(R.string.tweaks_power_button_action_follow_system),
            Preferences.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH to context.getString(R.string.tweaks_action_circle_to_search),
            Preferences.POWER_BUTTON_ACTION_DEFAULT_ASSISTANT to context.getString(R.string.tweaks_action_default_assistant)
        )
    }

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
                    }
                }
            }

            SmallTitle(stringResource(R.string.settings_system_ui_section_lockscreen))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = aodFullscreen,
                        onCheckedChange = onAodFullscreenChange,
                        title = stringResource(R.string.tweaks_aod_fullscreen_title),
                        summary = stringResource(R.string.tweaks_aod_fullscreen_summary)
                    )
                    SwitchPreference(
                        checked = hideFingerprint,
                        onCheckedChange = onHideFingerprintChange,
                        title = stringResource(R.string.tweaks_hide_fingerprint_title),
                        summary = stringResource(R.string.tweaks_hide_fingerprint_summary)
                    )
                    SwitchPreference(
                        checked = hideLockscreenStatusBar,
                        onCheckedChange = onHideLockscreenStatusBarChange,
                        title = stringResource(R.string.tweaks_hide_lockscreen_status_bar_title),
                        summary = stringResource(R.string.tweaks_hide_lockscreen_status_bar_summary)
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
                        // whitelist so every notification can appear on the lockscreen; the second
                        // stops the lockscreen from hiding notifications that were already shown
                        // after the last unlock.
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

            SmallTitle(stringResource(R.string.tweaks_control_center_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = sliderShowPercentage,
                        onCheckedChange = onSliderShowPercentageChange,
                        title = stringResource(R.string.tweaks_slider_show_percentage_title),
                        summary = stringResource(R.string.tweaks_slider_show_percentage_summary)
                    )
                    SwitchPreference(
                        checked = sliderSamePercentageStyle && sliderShowPercentage,
                        onCheckedChange = onSliderSamePercentageChange,
                        title = stringResource(R.string.tweaks_unify_percentage_style_title),
                        summary = stringResource(R.string.tweaks_unify_percentage_style_summary),
                        enabled = sliderShowPercentage
                    )
                }
            }

            SmallTitle(stringResource(R.string.tweaks_navigation_bar_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = hideGestureBar,
                        onCheckedChange = onHideGestureBarChange,
                        title = stringResource(R.string.tweaks_hide_gesture_bar_title),
                        summary = stringResource(R.string.tweaks_hide_gesture_bar_summary)
                    )
                    SwitchPreference(
                        checked = gestureBarRaiseLayout && hideGestureBar,
                        onCheckedChange = onGestureBarRaiseLayoutChange,
                        title = stringResource(R.string.tweaks_raise_layout_title),
                        summary = stringResource(R.string.tweaks_raise_layout_summary),
                        enabled = hideGestureBar
                    )
                    SwitchPreference(
                        checked = gestureBarActionsEnabled,
                        onCheckedChange = onGestureBarActionsEnabledChange,
                        title = stringResource(R.string.tweaks_gesture_bar_shortcuts_title),
                        summary = stringResource(R.string.tweaks_gesture_bar_shortcuts_summary),
                        enabled = GestureBarAction.actionsAvailable
                    )
                    AnimatedVisibility(
                        visible = gestureBarActionsEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.tweaks_long_press_action_title),
                                summary = stringResource(R.string.tweaks_long_press_action_summary),
                                items = gestureActionOptions.map { it.second },
                                selectedIndex = gestureActionOptions.indexOfFirst {
                                    it.first.persistedId == gestureBarLongPressAction
                                }.coerceAtLeast(0),
                                onSelectedIndexChange = { index ->
                                    gestureActionOptions.getOrNull(index)?.first?.persistedId?.let(
                                        onGestureBarLongPressActionChange
                                    )
                                }
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.tweaks_double_tap_action_title),
                                items = gestureActionOptions.map { it.second },
                                selectedIndex = gestureActionOptions.indexOfFirst {
                                    it.first.persistedId == gestureBarDoubleTapAction
                                }.coerceAtLeast(0),
                                onSelectedIndexChange = { index ->
                                    gestureActionOptions.getOrNull(index)?.first?.persistedId?.let(
                                        onGestureBarDoubleTapActionChange
                                    )
                                }
                            )
                        }
                    }
                    OverlayDropdownPreference(
                        title = stringResource(R.string.tweaks_power_button_action_title),
                        summary = stringResource(R.string.tweaks_power_button_action_summary),
                        items = powerButtonActionOptions.map { it.second },
                        selectedIndex = powerButtonActionOptions.indexOfFirst {
                            it.first == powerButtonAction
                        }.coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            powerButtonActionOptions.getOrNull(index)?.first?.let(
                                onPowerButtonActionChange
                            )
                        }
                    )
                    AnimatedVisibility(
                        visible = powerButtonAction != Preferences.POWER_BUTTON_ACTION_DISABLED,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = powerButtonHaptic,
                                onCheckedChange = onPowerButtonHapticChange,
                                title = stringResource(R.string.tweaks_power_button_haptic_title),
                                summary = stringResource(R.string.tweaks_power_button_haptic_summary)
                            )
                        }
                    }
                    if (!PlatformLevel.isOs4) {
                        SwitchPreference(
                            checked = miuiBackGestureHook,
                            onCheckedChange = onMiuiBackGestureHookChange,
                            title = stringResource(R.string.tweaks_aosp_back_gesture_title),
                            summary = if (launcherSupportsBackRoute) {
                                stringResource(R.string.tweaks_aosp_back_gesture_summary_launcher_scope)
                            } else {
                                stringResource(R.string.tweaks_aosp_back_gesture_summary)
                            }
                        )
                        SwitchPreference(
                            checked = crossTaskWallpaperBackground,
                            onCheckedChange = onCrossTaskWallpaperBackgroundChange,
                            title = stringResource(R.string.tweaks_cross_task_wallpaper_title),
                            summary = stringResource(R.string.tweaks_cross_task_wallpaper_summary),
                            enabled = miuiBackGestureHook
                        )
                        AnimatedVisibility(
                            visible = miuiBackGestureHook,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SwitchPreference(
                                    checked = aospBackIndicator,
                                    onCheckedChange = onAospBackIndicatorChange,
                                    title = stringResource(R.string.tweaks_hyperos_back_arrow_title),
                                    summary = stringResource(R.string.tweaks_hyperos_back_arrow_summary)
                                )
                                SwitchPreference(
                                    checked = aospBackHaptics,
                                    onCheckedChange = onAospBackHapticsChange,
                                    title = stringResource(R.string.tweaks_back_gesture_haptics_title),
                                    summary = stringResource(R.string.tweaks_back_gesture_haptics_summary),
                                    enabled = aospBackIndicator
                                )
                                SwitchPreference(
                                    checked = aospBackHapticsEnhanced,
                                    onCheckedChange = onAospBackHapticsEnhancedChange,
                                    title = stringResource(R.string.tweaks_enhanced_haptics_title),
                                    summary = stringResource(R.string.tweaks_enhanced_haptics_summary),
                                    enabled = aospBackIndicator && aospBackHaptics
                                )
                                SwitchPreference(
                                    checked = aospBackSlideAnimation,
                                    onCheckedChange = onAospBackSlideAnimationChange,
                                    title = stringResource(R.string.tweaks_slide_back_animation_title),
                                    summary = stringResource(R.string.tweaks_slide_back_animation_summary)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
