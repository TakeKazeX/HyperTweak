package com.takekazex.hypertweak.ui.page.systemui

import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit

import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.components.dialog.LocalRestartScopeHandled
import com.takekazex.hypertweak.ui.components.dialog.LocalRestartScopeRequest
import com.takekazex.hypertweak.ui.page.systemui.controlcenter.TileStateMessageDurationDialog
import com.takekazex.hypertweak.ui.app.LocalAppServices
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.settings.contract.TileStateMessageDuration
import com.takekazex.hypertweak.settings.contract.VolumeKeyStepPolicy
import com.takekazex.hypertweak.util.platform.PlatformLevel
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
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
    lockscreenFingerprintAvoid: Int,
    onLockscreenFingerprintAvoidChange: (Int) -> Unit,
    onNavigateToChargingDetail: () -> Unit,
    onNavigateToLockscreenBottomText: () -> Unit,
    lockscreenAllNotifications: Boolean,
    onLockscreenAllNotificationsChange: (Boolean) -> Unit,
    lockscreenKeepNotifications: Boolean,
    onLockscreenKeepNotificationsChange: (Boolean) -> Unit,
    sliderShowPercentage: Boolean,
    onSliderShowPercentageChange: (Boolean) -> Unit,
    sliderSamePercentageStyle: Boolean,
    onSliderSamePercentageChange: (Boolean) -> Unit,
    hideGestureBar: Boolean,
    onHideGestureBarChange: (Boolean) -> Unit,
    gestureBarRaiseLayout: Boolean,
    onGestureBarRaiseLayoutChange: (Boolean) -> Unit,
    hideGestureBarRecents: Boolean,
    onHideGestureBarRecentsChange: (Boolean) -> Unit,
    hideGestureBarLauncher: Boolean,
    onHideGestureBarLauncherChange: (Boolean) -> Unit,
    gestureBarAttachTransition: Boolean,
    onGestureBarAttachTransitionChange: (Boolean) -> Unit,
    powerButtonAction: Int,
    onPowerButtonActionChange: (Int) -> Unit,
    contextualSearchHaptics: Boolean,
    onContextualSearchHapticsChange: (Boolean) -> Unit,
    contextualSearchLongPress: Boolean,
    onContextualSearchLongPressChange: (Boolean) -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToMediaCard: () -> Unit,
    onNavigateToIconTuner: () -> Unit,
    onNavigateToPrivacyIndicator: () -> Unit,
    onNavigateToAodIcons: () -> Unit
) {
    val services = LocalAppServices.current
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current
    val upstreamBackGesture = com.takekazex.hypertweak.ui.effect.rememberUpstreamBackGestureInstalled()
    val scrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current
    var hideLockscreenDate by rememberSaveable {
        mutableStateOf(settingsStore.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE, false))
    }

    var volumeKeyStepCount by remember {
        mutableIntStateOf(
            settingsStore.getInt(
                PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT,
                PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT
            ).coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        )
    }
    var volumeKeyStepScope by remember {
        mutableIntStateOf(
            settingsStore.getInt(
                PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE,
                PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY
            ).coerceIn(
                PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY,
                PlatformSettingsKeys.VOLUME_KEY_SCOPE_ACTIVE_STREAM
            )
        )
    }
    var volumeKeySliderExpanded by remember { mutableStateOf(false) }
    val volumeKeySlider = rememberSliderEdit(volumeKeyStepCount.toFloat())
    val volumeKeySliderValue = volumeKeySlider.value
    val displayedVolumeKeyStepCount = volumeKeySliderValue.roundToInt().coerceIn(
        VolumeKeyStepPolicy.MIN_STEPS,
        VolumeKeyStepPolicy.MAX_STEPS
    )
    fun setVolumeKeyStepCount(value: Int) {
        val resolved = value.coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        volumeKeyStepCount = resolved
        settingsStore.putInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, resolved)
        settingsStore.flush()
    }
    fun setVolumeKeyStepScope(value: Int) {
        val resolved = value.coerceIn(
            PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY,
            PlatformSettingsKeys.VOLUME_KEY_SCOPE_ACTIVE_STREAM
        )
        volumeKeyStepScope = resolved
        settingsStore.putInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE, resolved)
        settingsStore.flush()
    }

    var tileMessageDialog by rememberSaveable { mutableStateOf(false) }
    var tileMessageSeconds by rememberSaveable {
        mutableFloatStateOf(TileStateMessageDuration.normalize(settingsStore.getInt(
            SystemUiSettingsKeys.KEY_CC_TILE_MESSAGE_DURATION_MS, TileStateMessageDuration.DEFAULT_MS
        )) / 1000f)
    }

    val tileMessageSlider = rememberSliderEdit(tileMessageSeconds)

    fun setTileMessageSeconds(value: Float) {
        val milliseconds = TileStateMessageDuration.normalize((value * 1000).roundToInt())
        tileMessageSeconds = milliseconds / 1000f
        settingsStore.putInt(SystemUiSettingsKeys.KEY_CC_TILE_MESSAGE_DURATION_MS, milliseconds)
        settingsStore.flush()
    }

    var freeformBlur by remember { mutableStateOf(settingsStore.platform.freeformBlurTransition()) }

    val powerButtonActionOptions = remember {
        listOf(
            PlatformSettingsKeys.POWER_BUTTON_ACTION_DISABLED to context.getString(R.string.tweaks_power_button_action_follow_system),
            PlatformSettingsKeys.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH to context.getString(R.string.tweaks_action_circle_to_search),
            PlatformSettingsKeys.POWER_BUTTON_ACTION_GOOGLE_LENS to context.getString(R.string.tweaks_action_google_lens),
            PlatformSettingsKeys.POWER_BUTTON_ACTION_DEFAULT_ASSISTANT to context.getString(R.string.tweaks_action_default_assistant)
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
                        summary = stringResource(if (volumeKeyStepScope == PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY) R.string.volume_key_scope_media else R.string.volume_key_scope_active_stream),
                        showValue = false,
                        items = listOf(
                            stringResource(R.string.volume_key_scope_media),
                            stringResource(R.string.volume_key_scope_active_stream)
                        ),
                        selectedIndex = volumeKeyStepScope,
                        onSelectedIndexChange = ::setVolumeKeyStepScope
                    )
                    ArrowPreference(
                        title = stringResource(R.string.volume_key_step_title),
                        summary = pluralStringResource(
                            R.plurals.volume_key_step_summary,
                            displayedVolumeKeyStepCount,
                            displayedVolumeKeyStepCount,
                            (100f / displayedVolumeKeyStepCount).roundToInt()
                        ),
                        endActions = {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.volume_key_step_value,
                                    displayedVolumeKeyStepCount,
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
                                onValueChange = volumeKeySlider::update,
                                onValueChangeFinished = {
                                    volumeKeySlider.finish { it.roundToInt().toFloat() }?.let { setVolumeKeyStepCount(it.toInt()) }
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

            SmallTitle(stringResource(R.string.notification_media_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
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

            SmallTitle(stringResource(R.string.settings_system_ui_section_multitasking))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = freeformBlur,
                        onCheckedChange = { value ->
                            freeformBlur = value
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_FREEFORM_BLUR_TRANSITION, value)
                        },
                        title = stringResource(R.string.settings_freeform_blur_title),
                        summary = stringResource(R.string.settings_freeform_blur_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.settings_system_ui_section_status_bar))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
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
                            settingsStore.putBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE, value)
                            settingsStore.flush()
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

            SmallTitle(stringResource(R.string.tweaks_control_center_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
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

            TileStateMessageDurationDialog(
                show = tileMessageDialog,
                current = tileMessageSeconds,
                onConfirm = ::setTileMessageSeconds,
                onDismiss = { tileMessageDialog = false }
            )

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
                        checked = gestureBarAttachTransition,
                        onCheckedChange = onGestureBarAttachTransitionChange,
                        title = stringResource(R.string.tweaks_gesture_bar_attach_transition_title),
                        summary = stringResource(R.string.tweaks_gesture_bar_attach_transition_summary),
                        enabled = !hideGestureBar
                    )
                    SwitchPreference(
                        checked = hideGestureBarRecents,
                        onCheckedChange = onHideGestureBarRecentsChange,
                        title = stringResource(R.string.tweaks_hide_gesture_bar_recents_title),
                        summary = stringResource(R.string.tweaks_hide_gesture_bar_recents_summary),
                        enabled = !hideGestureBar
                    )
                    SwitchPreference(
                        checked = hideGestureBarLauncher,
                        onCheckedChange = onHideGestureBarLauncherChange,
                        title = stringResource(R.string.tweaks_hide_gesture_bar_launcher_title),
                        summary = stringResource(R.string.tweaks_hide_gesture_bar_launcher_summary),
                        enabled = !hideGestureBar
                    )
                    SwitchPreference(
                        checked = contextualSearchLongPress,
                        onCheckedChange = { checked ->
                            onContextualSearchLongPressChange(checked)
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
                            checked = contextualSearchHaptics,
                            onCheckedChange = onContextualSearchHapticsChange,
                            title = stringResource(R.string.tweaks_cts_haptics_title),
                            summary = stringResource(R.string.tweaks_cts_haptics_summary),
                            enabled = upstreamBackGesture == false && contextualSearchLongPress
                        )
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
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
