package com.takekazex.hypertweak.ui.page

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

@Composable
fun TweaksScreenContent(
    padding: PaddingValues,
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
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
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val contentReady = rememberContentReady()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val gestureActionLabels = remember {
        listOf(
            "Disabled",
            "Default assistant",
            "Circle to Search",
            "Gemini (direct)",
            "ChatGPT (direct)"
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Features",
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
            SmallTitle(text = "Lockscreen & Display")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = aodFullscreen,
                        onCheckedChange = onAodFullscreenChange,
                        title = "Always-On Display Fullscreen",
                        summary = "Unlock full screen background support for AOD"
                    )
                    SwitchPreference(
                        checked = hideFingerprint,
                        onCheckedChange = onHideFingerprintChange,
                        title = "Hide Lockscreen Fingerprint",
                        summary = "Completely remove the fingerprint sensor circle icon on lockscreen"
                    )
                    SwitchPreference(
                        checked = hideLockscreenStatusBar,
                        onCheckedChange = onHideLockscreenStatusBarChange,
                        title = "Hide Lockscreen Status Bar",
                        summary = "Hide the clock and status icons while the device is locked"
                    )
                }
            }

            // Scope 2: Control Center
            SmallTitle(text = "Control Center")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = sliderShowPercentage,
                        onCheckedChange = onSliderShowPercentageChange,
                        title = "Slider Show Percentage Value",
                        summary = "Show percentage values on the brightness and volume sliders"
                    )
                    SwitchPreference(
                        checked = sliderSamePercentageStyle && sliderShowPercentage,
                        onCheckedChange = onSliderSamePercentageChange,
                        title = "Unify Percentage Style",
                        summary = "Always keep the volume slider percentage text visible to match the brightness style",
                        enabled = sliderShowPercentage
                    )
                }
            }

            // Scope 3: Navigation Bar
            SmallTitle(text = "Navigation Bar")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = hideGestureBar,
                        onCheckedChange = onHideGestureBarChange,
                        title = "Hide Bottom Gesture Bar",
                        summary = "Hide the bottom gesture line and multitasking split-screen bar"
                    )
                    SwitchPreference(
                        checked = gestureBarRaiseLayout && hideGestureBar,
                        onCheckedChange = onGestureBarRaiseLayoutChange,
                        title = "Raise Layout",
                        summary = "Keep the reserved navigation bar space so app content sits above the gesture area",
                        enabled = hideGestureBar
                    )
                    SwitchPreference(
                        checked = gestureBarActionsEnabled,
                        onCheckedChange = onGestureBarActionsEnabledChange,
                        title = "Gesture Bar Shortcuts",
                        summary = "Handle long press and double tap in SystemUI"
                    )
                    AnimatedVisibility(
                        visible = gestureBarActionsEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OverlayDropdownPreference(
                                title = "Long Press Action",
                                summary = "Circle to Search and direct actions do not require a default assistant",
                                items = gestureActionLabels,
                                selectedIndex = gestureBarLongPressAction.coerceIn(
                                    0,
                                    gestureActionLabels.lastIndex
                                ),
                                onSelectedIndexChange = onGestureBarLongPressActionChange
                            )
                            OverlayDropdownPreference(
                                title = "Double Tap Action",
                                items = gestureActionLabels,
                                selectedIndex = gestureBarDoubleTapAction.coerceIn(
                                    0,
                                    gestureActionLabels.lastIndex
                                ),
                                onSelectedIndexChange = onGestureBarDoubleTapActionChange
                            )
                        }
                    }
                    SwitchPreference(
                        checked = miuiBackGestureHook,
                        onCheckedChange = onMiuiBackGestureHookChange,
                        title = "AOSP Back Gesture",
                        summary = if (launcherSupportsBackRoute) {
                            "Restore the AOSP back gesture pipeline in MIUI SystemUI. " +
                                "Needs \"System Launcher\" in the module's LSPosed scope, " +
                                "which owns the screen edges on this launcher"
                        } else {
                            "Restore the AOSP back gesture pipeline in MIUI SystemUI"
                        }
                    )
                    SwitchPreference(
                        checked = crossTaskWallpaperBackground,
                        onCheckedChange = onCrossTaskWallpaperBackgroundChange,
                        title = "Cross Task Wallpaper Background",
                        summary = "Use a blurred wallpaper behind cross-task back animations",
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
                                title = "HyperOS Back Arrow",
                                summary = "Replaces the AOSP indicator with the HyperOS arrow. " +
                                    "Turn off to get the AOSP indicator back, at the cost of haptics"
                            )
                            SwitchPreference(
                                checked = aospBackHaptics,
                                onCheckedChange = onAospBackHapticsChange,
                                title = "Back Gesture Haptics",
                                summary = "Vibrate when the gesture arms and triggers. " +
                                    "Driven by the arrow overlay, so it needs HyperOS Back Arrow",
                                enabled = aospBackIndicator
                            )
                            SwitchPreference(
                                checked = aospBackHapticsEnhanced,
                                onCheckedChange = onAospBackHapticsEnhancedChange,
                                title = "Enhanced Haptics",
                                summary = "Use the richer HyperOS haptic pattern",
                                enabled = aospBackIndicator && aospBackHaptics
                            )
                            SwitchPreference(
                                checked = aospBackSlideAnimation,
                                onCheckedChange = onAospBackSlideAnimationChange,
                                title = "Slide Back Animation",
                                summary = "Slide the previous activity in behind the gesture"
                            )
                        }
                    }
                }
            }

            // Scope 4: System Core
            SmallTitle(text = "System Core")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = removeGms,
                        onCheckedChange = onRemoveGmsChange,
                        title = "Bypass GMS China ROM Restrictions",
                        summary = "Remove Google Play Services installation restrictions on Chinese firmware"
                    )
                    SwitchPreference(
                        checked = unlockPasskey,
                        onCheckedChange = onUnlockPasskeyChange,
                        title = "Unlock Google Passkey / Credential Manager",
                        summary = "Allow using Google Passkey and third-party credential managers on domestic MIUI/HyperOS"
                    )
                    SwitchPreference(
                        checked = fcmLiveEnabled,
                        onCheckedChange = onFcmLiveEnabledChange,
                        title = "Fix Google Push (FCM Live)",
                        summary = "Remove HyperOS restrictions on Google Cloud Messaging. May increase battery usage. Requires reboot and PowerKeeper restart to apply."
                    )
                }
            }

            // Scope 5: Bluetooth
            SmallTitle(text = "Bluetooth")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = disableSpatialAudio,
                        onCheckedChange = onDisableSpatialAudioChange,
                        title = "Block Spatial Audio",
                        summary = "AirPods only, including AirPods Max. Prevent spatial audio from being enabled. Restart Settings, MiLink Service, and Xiaomi Bluetooth after changing."
                    )
                    SwitchPreference(
                        checked = forceAdaptiveAnc,
                        onCheckedChange = onForceAdaptiveAncChange,
                        title = "Force Adaptive ANC",
                        summary = "AirPods only, including AirPods Max. Replace Off mode with Adaptive noise cancellation. Restart Settings, MiLink Service, and Xiaomi Bluetooth after changing."
                    )
                }
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
