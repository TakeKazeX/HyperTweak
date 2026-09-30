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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlin.math.roundToInt
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.NativeRuleConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.system.VolumeKeyStepPolicy
import com.takekazex.hypertweak.util.PlatformLevel
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * System UI tweaks (Features tab → System UI). This second-level page groups notification fixes,
 * multitasking transitions, volume-key steps, lockscreen and display options, media-card switches,
 * control-center sliders, and navigation-bar settings. Existing tracked switches stay hoisted in
 * [MainActivity]; self-contained groups read their saved preferences here and offer local restarts.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun SystemUIPage(
    onBack: () -> Unit,
    immediateMonetRefresh: Boolean,
    onImmediateMonetRefreshChange: (Boolean) -> Unit,
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    hideFingerprintAod: Boolean,
    onHideFingerprintAodChange: (Boolean) -> Unit,
    hideFingerprintLockscreen: Boolean,
    onHideFingerprintLockscreenChange: (Boolean) -> Unit,
    hideFingerprintAppAuth: Boolean,
    onHideFingerprintAppAuthChange: (Boolean) -> Unit,
    hideLockscreenStatusBar: Boolean,
    onHideLockscreenStatusBarChange: (Boolean) -> Unit,
    notificationHeaderClockSeconds: Boolean,
    onNotificationHeaderClockSecondsChange: (Boolean) -> Unit,
    notificationMonetTextColor: Boolean,
    onNotificationMonetTextColorChange: (Boolean) -> Unit,
    notificationFontWeight: Boolean,
    onNotificationFontWeightChange: (Boolean) -> Unit,
    lockscreenFingerprintAvoid: Int,
    onLockscreenFingerprintAvoidChange: (Int) -> Unit,
    onNavigateToChargingDetail: () -> Unit,
    onNavigateToLockscreenBottomText: () -> Unit,
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
    powerButtonAction: Int,
    onPowerButtonActionChange: (Int) -> Unit,
    contextualSearchLongPress: Boolean,
    onContextualSearchLongPressChange: (Boolean) -> Unit,
    onNavigateToNotificationHeader: () -> Unit,
    onNavigateToIconTuner: () -> Unit,
    onNavigateToAodIcons: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current
    var hideLockscreenDate by rememberSaveable {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_DATE, false))
    }

    var volumeKeyStepCount by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_VOLUME_KEY_STEP_COUNT,
                Preferences.DEFAULT_VOLUME_KEY_STEP_COUNT
            ).coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        )
    }
    var volumeKeyStepScope by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_VOLUME_KEY_STEP_SCOPE,
                Preferences.VOLUME_KEY_SCOPE_MEDIA_ONLY
            ).coerceIn(
                Preferences.VOLUME_KEY_SCOPE_MEDIA_ONLY,
                Preferences.VOLUME_KEY_SCOPE_ACTIVE_STREAM
            )
        )
    }
    var volumeKeySliderExpanded by remember { mutableStateOf(false) }
    var volumeKeySliderValue by remember(volumeKeyStepCount) {
        mutableFloatStateOf(volumeKeyStepCount.toFloat())
    }
    val displayedVolumeKeyStepCount = volumeKeySliderValue.roundToInt().coerceIn(
        VolumeKeyStepPolicy.MIN_STEPS,
        VolumeKeyStepPolicy.MAX_STEPS
    )
    fun setVolumeKeyStepCount(value: Int) {
        val resolved = value.coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        volumeKeyStepCount = resolved
        Preferences.putInt(Preferences.KEY_VOLUME_KEY_STEP_COUNT, resolved)
        Preferences.flush()
    }
    fun setVolumeKeyStepScope(value: Int) {
        val resolved = value.coerceIn(
            Preferences.VOLUME_KEY_SCOPE_MEDIA_ONLY,
            Preferences.VOLUME_KEY_SCOPE_ACTIVE_STREAM
        )
        volumeKeyStepScope = resolved
        Preferences.putInt(Preferences.KEY_VOLUME_KEY_STEP_SCOPE, resolved)
        Preferences.flush()
    }

    var notifMoreSettings by remember { mutableStateOf(Preferences.notificationMoreSettings()) }
    var notifBadge by remember { mutableStateOf(Preferences.notificationBadge()) }
    var notifBlockFold by remember { mutableStateOf(Preferences.notificationBlockFold()) }
    var notifSettingsRestartPending by rememberSaveable { mutableStateOf(false) }
    var notifSystemUiRestartPending by rememberSaveable { mutableStateOf(false) }
    var freeformBlur by remember { mutableStateOf(Preferences.freeformBlurTransition()) }
    var freeformBlurRestartPending by rememberSaveable { mutableStateOf(false) }

    val powerButtonActionOptions = remember {
        listOf(
            Preferences.POWER_BUTTON_ACTION_DISABLED to context.getString(R.string.tweaks_power_button_action_follow_system),
            Preferences.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH to context.getString(R.string.tweaks_action_circle_to_search),
            Preferences.POWER_BUTTON_ACTION_GOOGLE_LENS to context.getString(R.string.tweaks_action_google_lens),
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

            SmallTitle(stringResource(R.string.volume_key_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.volume_key_scope_title),
                        summary = stringResource(R.string.volume_key_scope_summary),
                        items = listOf(
                            stringResource(R.string.volume_key_scope_media),
                            stringResource(R.string.volume_key_scope_active_stream)
                        ),
                        selectedIndex = volumeKeyStepScope,
                        onSelectedIndexChange = ::setVolumeKeyStepScope
                    )
                    ArrowPreference(
                        title = stringResource(R.string.volume_key_step_title),
                        summary = stringResource(
                            R.string.volume_key_step_summary,
                            displayedVolumeKeyStepCount,
                            (100f / displayedVolumeKeyStepCount).roundToInt()
                        ),
                        endActions = {
                            Text(
                                text = stringResource(
                                    R.string.volume_key_step_value,
                                    displayedVolumeKeyStepCount
                                ),
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = { volumeKeySliderExpanded = !volumeKeySliderExpanded },
                        holdDownState = volumeKeySliderExpanded,
                        bottomAction = {
                            Slider(
                                value = volumeKeySliderValue.coerceIn(
                                    VolumeKeyStepPolicy.MIN_STEPS.toFloat(),
                                    VolumeKeyStepPolicy.MAX_STEPS.toFloat()
                                ),
                                onValueChange = { volumeKeySliderValue = it },
                                onValueChangeFinished = {
                                    setVolumeKeyStepCount(volumeKeySliderValue.roundToInt())
                                },
                                valueRange = VolumeKeyStepPolicy.MIN_STEPS.toFloat()..
                                    VolumeKeyStepPolicy.MAX_STEPS.toFloat(),
                                steps = VolumeKeyStepPolicy.MAX_STEPS -
                                    VolumeKeyStepPolicy.MIN_STEPS - 1,
                                showKeyPoints = true,
                                keyPoints = listOf(5f, 15f, 100f),
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step
                            )
                        }
                    )
                }
            }
            IntValueDialog(
                show = volumeKeySliderExpanded,
                title = stringResource(R.string.volume_key_step_title),
                summary = stringResource(R.string.volume_key_step_dialog_summary),
                suffix = stringResource(R.string.volume_key_step_suffix),
                range = VolumeKeyStepPolicy.MIN_STEPS..VolumeKeyStepPolicy.MAX_STEPS,
                currentValue = { volumeKeyStepCount },
                emptyValue = volumeKeyStepCount,
                onValueConfirmed = ::setVolumeKeyStepCount,
                onDismissRequest = { volumeKeySliderExpanded = false }
            )

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
                    if (notifSettingsRestartPending) {
                        val restartSelection = RestartScopeSelection(settings = true)
                        ArrowPreference(
                            title = stringResource(R.string.settings_notification_restart_settings_title),
                            summary = stringResource(R.string.settings_notification_restart_settings_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = restartSelection
                                )
                                handleRestartedScopes(restartSelection)
                                notifSettingsRestartPending = false
                            }
                        )
                    }
                    if (notifSystemUiRestartPending) {
                        val restartSelection = RestartScopeSelection(systemUi = true)
                        ArrowPreference(
                            title = stringResource(R.string.aosp_restart_system_ui),
                            summary = stringResource(R.string.aosp_restart_system_ui_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = restartSelection
                                )
                                handleRestartedScopes(restartSelection)
                                notifSystemUiRestartPending = false
                            }
                        )
                    }
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
                            checked = notificationFontWeight,
                            onCheckedChange = onNotificationFontWeightChange,
                            title = stringResource(R.string.settings_notification_font_weight_title),
                            summary = stringResource(R.string.settings_notification_font_weight_summary)
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.settings_system_ui_section_multitasking))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = freeformBlur,
                        onCheckedChange = { value ->
                            freeformBlur = value
                            Preferences.putBoolean(Preferences.KEY_FREEFORM_BLUR_TRANSITION, value)
                            Preferences.flush()
                            freeformBlurRestartPending = true
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
                        },
                        title = stringResource(R.string.settings_freeform_blur_title),
                        summary = stringResource(R.string.settings_freeform_blur_summary)
                    )
                    if (freeformBlurRestartPending) {
                        val restartSelection = RestartScopeSelection(systemUi = true)
                        ArrowPreference(
                            title = stringResource(R.string.aosp_restart_system_ui),
                            summary = stringResource(R.string.aosp_restart_system_ui_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = restartSelection
                                )
                                handleRestartedScopes(restartSelection)
                                freeformBlurRestartPending = false
                            }
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.settings_system_ui_section_status_bar))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_icon_tuner),
                    summary = stringResource(R.string.settings_icon_tuner_summary),
                    onClick = onNavigateToIconTuner
                )
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
                    ArrowPreference(
                        title = stringResource(R.string.settings_aod_icons_title),
                        summary = stringResource(R.string.settings_aod_icons_summary),
                        onClick = onNavigateToAodIcons
                    )
                    SwitchPreference(
                        checked = hideFingerprintLockscreen,
                        onCheckedChange = onHideFingerprintLockscreenChange,
                        title = stringResource(R.string.tweaks_hide_fingerprint_lockscreen_title),
                        summary = stringResource(R.string.tweaks_hide_fingerprint_lockscreen_summary)
                    )
                    SwitchPreference(
                        checked = hideFingerprintAod,
                        onCheckedChange = onHideFingerprintAodChange,
                        title = stringResource(R.string.tweaks_hide_fingerprint_aod_title),
                        summary = stringResource(R.string.tweaks_hide_fingerprint_aod_summary)
                    )
                    SwitchPreference(
                        checked = hideFingerprintAppAuth,
                        onCheckedChange = onHideFingerprintAppAuthChange,
                        title = stringResource(R.string.tweaks_hide_fingerprint_app_auth_title),
                        summary = stringResource(R.string.tweaks_hide_fingerprint_app_auth_summary)
                    )
                    SwitchPreference(
                        checked = hideLockscreenStatusBar,
                        onCheckedChange = onHideLockscreenStatusBarChange,
                        title = stringResource(R.string.tweaks_hide_lockscreen_status_bar_title),
                        summary = stringResource(R.string.tweaks_hide_lockscreen_status_bar_summary)
                    )
                    SwitchPreference(
                        checked = hideLockscreenDate,
                        onCheckedChange = { value ->
                            hideLockscreenDate = value
                            Preferences.putBoolean(Preferences.KEY_HIDE_LOCKSCREEN_DATE, value)
                            Preferences.flush()
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
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
                        checked = contextualSearchLongPress,
                        onCheckedChange = { checked ->
                            onContextualSearchLongPressChange(checked)
                            // Keep the published record in step with the preference. The payload is
                            // not driven by this file -- the launcher cannot read it -- but takes
                            // the switch from `NativeRules.applyRuleSwitches` when the launcher
                            // next loads the module, which is why the change raises a
                            // launcher-restart prompt. `checked` is passed explicitly rather than
                            // re-read, so the published value cannot lag the write above.
                            NativeRuleConfig.publish(
                                context,
                                Preferences.hideRecentsClearButton(),
                                Preferences.openedFolderColumns(),
                                checked
                            )
                        },
                        title = stringResource(R.string.tweaks_cts_long_press_title),
                        summary = stringResource(R.string.tweaks_cts_long_press_summary)
                    )
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
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
