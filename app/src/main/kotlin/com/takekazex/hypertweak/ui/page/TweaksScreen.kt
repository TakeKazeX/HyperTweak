package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarAction
import com.takekazex.hypertweak.util.PlatformLevel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import com.takekazex.hypertweak.ui.effect.rememberContentReady

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun TweaksScreenContent(
    padding: PaddingValues,
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    quickShareEnabled: Boolean,
    onQuickShareEnabledChange: (Boolean) -> Unit,
    hideFingerprint: Boolean,
    onHideFingerprintChange: (Boolean) -> Unit,
    hideLockscreenStatusBar: Boolean,
    onHideLockscreenStatusBarChange: (Boolean) -> Unit,
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
    powerButtonCts: Boolean,
    onPowerButtonCtsChange: (Boolean) -> Unit,
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
    launcherSupportsBackRoute: Boolean,
    unlockPasskey: Boolean,
    onUnlockPasskeyChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,
    fcmLiveEnabled: Boolean,
    onFcmLiveEnabledChange: (Boolean) -> Unit,
    backdrop: LayerBackdrop
) {
    val context = LocalContext.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val contentReady = rememberContentReady()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val gestureActionOptions = remember {
        listOf(
            GestureBarAction.DISABLED to context.getString(R.string.tweaks_action_disabled),
            GestureBarAction.DEFAULT_ASSISTANT to context.getString(R.string.tweaks_action_default_assistant),
            GestureBarAction.CIRCLE_TO_SEARCH to context.getString(R.string.tweaks_action_circle_to_search)
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.tweaks_features),
                modifier = if (contentReady) {
                    Modifier.textureBlur(
                        backdrop = topBarBackdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(blendColors = listOf(
                            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                        ))
                    )
                } else {
                    Modifier
                },
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (contentReady) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
            Spacer(modifier = Modifier.height(8.dp))

            // Scope 1: Lockscreen & Display
            SmallTitle(text = stringResource(R.string.tweaks_lockscreen_display_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                }
            }

            // Scope 2: Control Center
            SmallTitle(text = stringResource(R.string.tweaks_control_center_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
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

            // Scope 3: Navigation Bar
            SmallTitle(text = stringResource(R.string.tweaks_navigation_bar_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                    SwitchPreference(
                        checked = powerButtonCts,
                        onCheckedChange = onPowerButtonCtsChange,
                        title = stringResource(R.string.tweaks_power_button_cts_title),
                        summary = stringResource(R.string.tweaks_power_button_cts_summary)
                    )
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

            // Scope 4: System Core
            SmallTitle(text = stringResource(R.string.tweaks_system_core_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = removeGms,
                        onCheckedChange = onRemoveGmsChange,
                        title = stringResource(R.string.tweaks_gms_bypass_title),
                        summary = stringResource(R.string.tweaks_gms_bypass_summary)
                    )
                    SwitchPreference(
                        checked = quickShareEnabled,
                        onCheckedChange = onQuickShareEnabledChange,
                        title = stringResource(R.string.tweaks_quick_share_title),
                        summary = stringResource(R.string.tweaks_quick_share_summary)
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

            // Scope 5: Bluetooth
            SmallTitle(text = stringResource(R.string.tweaks_bluetooth_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = disableSpatialAudio,
                        onCheckedChange = onDisableSpatialAudioChange,
                        title = stringResource(R.string.tweaks_block_spatial_audio_title),
                        summary = stringResource(R.string.tweaks_block_spatial_audio_summary)
                    )
                    SwitchPreference(
                        checked = forceAdaptiveAnc,
                        onCheckedChange = onForceAdaptiveAncChange,
                        title = stringResource(R.string.tweaks_force_adaptive_anc_title),
                        summary = stringResource(R.string.tweaks_force_adaptive_anc_summary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}