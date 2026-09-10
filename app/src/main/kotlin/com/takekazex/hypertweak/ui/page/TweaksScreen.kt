package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
import com.takekazex.hypertweak.util.RestartScopeSelection
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun TweaksScreenContent(
    padding: PaddingValues,
    onNavigateToSystemUi: () -> Unit,
    onNavigateToDownloadManager: () -> Unit,
    onNavigateToSecurityCenter: () -> Unit,
    onNavigateToAospRestore: () -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    quickShareEnabled: Boolean,
    onQuickShareEnabledChange: (Boolean) -> Unit,
    fullScreenTranslate: Boolean,
    onFullScreenTranslateChange: (Boolean) -> Unit,
    askAboutScreen: Boolean,
    onAskAboutScreenChange: (Boolean) -> Unit,
    showGoogleServicesInSettings: Boolean,
    onShowGoogleServicesInSettingsChange: (Boolean) -> Unit,
    paModelSpoofEnabled: Boolean,
    onPaModelSpoofEnabledChange: (Boolean) -> Unit,
    unlockPasskey: Boolean,
    onUnlockPasskeyChange: (Boolean) -> Unit,
    unlockThirdPartyDarkMode: Boolean,
    onUnlockThirdPartyDarkModeChange: (Boolean) -> Unit,
    disableVideoRingback: Boolean,
    onDisableVideoRingbackChange: (Boolean) -> Unit,
    fcmLiveEnabled: Boolean,
    onFcmLiveEnabledChange: (Boolean) -> Unit,
    lbeClipboardToast: Boolean,
    onLbeClipboardToastChange: (Boolean) -> Unit,
    disableMiTrustRiskMonitoring: Boolean,
    onDisableMiTrustRiskMonitoringChange: (Boolean) -> Unit,
    disableGuardEnvironmentCheck: Boolean,
    onDisableGuardEnvironmentCheckChange: (Boolean) -> Unit,
    blockGuardUploadAppList: Boolean,
    onBlockGuardUploadAppListChange: (Boolean) -> Unit,
    blockMiLinkHpplayFiles: Boolean,
    onBlockMiLinkHpplayFilesChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,
    focusNotificationUnlockWhitelist: Boolean,
    onFocusNotificationUnlockWhitelistChange: (Boolean) -> Unit,
    mediaSuperIslandUnlockWhitelist: Boolean,
    onMediaSuperIslandUnlockWhitelistChange: (Boolean) -> Unit,
    xmsfUnlockFocusAuth: Boolean,
    onXmsfUnlockFocusAuthChange: (Boolean) -> Unit,
    backdrop: LayerBackdrop
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val contentReady = rememberContentReady()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
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

            // Keep the pages that operate on a complete system component together, with a visual
            // cue for the app each entry opens.
            SmallTitle(text = stringResource(R.string.tweaks_system_components_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_aosp_restore),
                        summary = stringResource(R.string.settings_aosp_restore_summary),
                        startAction = {
                            ApplicationIcon(
                                packageName = RestartScopeSelection.PACKAGE_SETTINGS,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.settings_aosp_restore)
                            )
                        },
                        onClick = onNavigateToAospRestore
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_system_ui),
                        summary = stringResource(R.string.settings_system_ui_summary),
                        startAction = {
                            ApplicationIcon(
                                packageName = RestartScopeSelection.PACKAGE_SYSTEM_UI,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.settings_system_ui)
                            )
                        },
                        onClick = onNavigateToSystemUi
                    )
                    ArrowPreference(
                        title = stringResource(R.string.download_manager_title),
                        summary = stringResource(R.string.download_manager_summary),
                        startAction = {
                            ApplicationIcon(
                                packageName = RestartScopeSelection.PACKAGE_DOWNLOADS_UI,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.download_manager_title)
                            )
                        },
                        onClick = onNavigateToDownloadManager
                    )
                    ArrowPreference(
                        title = stringResource(R.string.tweaks_security_center_title),
                        summary = stringResource(R.string.tweaks_security_center_summary),
                        startAction = {
                            ApplicationIcon(
                                packageName = RestartScopeSelection.PACKAGE_SECURITY_CENTER,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.tweaks_security_center_title)
                            )
                        },
                        onClick = onNavigateToSecurityCenter
                    )
                }
            }

            SmallTitle(text = stringResource(R.string.tweaks_mitrust_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = disableMiTrustRiskMonitoring,
                    onCheckedChange = onDisableMiTrustRiskMonitoringChange,
                    title = stringResource(R.string.tweaks_disable_risk_monitoring_title),
                    summary = stringResource(R.string.tweaks_disable_risk_monitoring_summary)
                )
            }

            SmallTitle(text = stringResource(R.string.tweaks_guard_provider_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = disableGuardEnvironmentCheck,
                        onCheckedChange = onDisableGuardEnvironmentCheckChange,
                        title = stringResource(R.string.tweaks_disable_environment_check_title),
                        summary = stringResource(R.string.tweaks_disable_environment_check_summary)
                    )
                    SwitchPreference(
                        checked = blockGuardUploadAppList,
                        onCheckedChange = onBlockGuardUploadAppListChange,
                        title = stringResource(R.string.tweaks_block_upload_app_list_title),
                        summary = stringResource(R.string.tweaks_block_upload_app_list_summary)
                    )
                }
            }

            SmallTitle(text = stringResource(R.string.tweaks_milink_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = blockMiLinkHpplayFiles,
                    onCheckedChange = onBlockMiLinkHpplayFilesChange,
                    title = stringResource(R.string.tweaks_block_milink_hpplay_files_title),
                    summary = stringResource(R.string.tweaks_block_milink_hpplay_files_summary)
                )
            }

            SmallTitle(text = stringResource(R.string.tweaks_system_core_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
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

            SmallTitle(text = stringResource(R.string.tweaks_personal_assistant_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = paModelSpoofEnabled,
                        onCheckedChange = onPaModelSpoofEnabledChange,
                        title = stringResource(R.string.settings_pa_model_spoof_title),
                        summary = stringResource(R.string.settings_pa_model_spoof_summary)
                    )
                    AnimatedVisibility(
                        visible = paModelSpoofEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        ModelSpoofValuesRow()
                    }
                }
            }

            SmallTitle(text = stringResource(R.string.tweaks_display_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = unlockThirdPartyDarkMode,
                    onCheckedChange = onUnlockThirdPartyDarkModeChange,
                    title = stringResource(R.string.settings_unlock_third_party_dark_mode_title),
                    summary = stringResource(R.string.settings_unlock_third_party_dark_mode_summary)
                )
            }

            SmallTitle(text = stringResource(R.string.tweaks_phone_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = disableVideoRingback,
                    onCheckedChange = onDisableVideoRingbackChange,
                    title = stringResource(R.string.settings_disable_video_ringback_title),
                    summary = stringResource(R.string.settings_disable_video_ringback_summary)
                )
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

            // Super Island: keep the normal, media, and xmsf gates together on this page (no
            // second-level page), while leaving each switch independently controllable.
            SmallTitle(text = stringResource(R.string.tweaks_focus_notification_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = focusNotificationUnlockWhitelist,
                        onCheckedChange = onFocusNotificationUnlockWhitelistChange,
                        title = stringResource(R.string.tweaks_focus_unlock_whitelist_title),
                        summary = stringResource(R.string.tweaks_focus_unlock_whitelist_summary)
                    )
                    SwitchPreference(
                        checked = mediaSuperIslandUnlockWhitelist,
                        onCheckedChange = onMediaSuperIslandUnlockWhitelistChange,
                        title = stringResource(R.string.tweaks_media_super_island_unlock_whitelist_title),
                        summary = stringResource(R.string.tweaks_media_super_island_unlock_whitelist_summary)
                    )
                    SwitchPreference(
                        checked = xmsfUnlockFocusAuth,
                        onCheckedChange = onXmsfUnlockFocusAuthChange,
                        title = stringResource(R.string.tweaks_xmsf_unlock_focus_auth_title),
                        summary = stringResource(R.string.tweaks_xmsf_unlock_focus_auth_summary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

/** Loads the target component's launcher icon without blocking the Compose main thread. */
@Composable
private fun ApplicationIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    contentDescription: String
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(96, 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    val resolvedIcon = icon
    if (resolvedIcon != null) {
        Image(
            bitmap = resolvedIcon,
            contentDescription = contentDescription,
            modifier = modifier.size(32.dp)
        )
    } else {
        // Keep the slot stable while the package icon is unavailable without substituting a
        // module or semantic icon.
        Spacer(modifier = modifier.size(32.dp))
    }
}

/**
 * Editable phoneModel / phoneDevice used by the Smart-Assistant model spoof
 * ([ModelSpoofHooker]). Reads and writes [Preferences] directly, defaulting to the Xiaomi 12S
 * Ultra (`2203121C` / `thor`). Only shown while the master switch is on.
 */
@Composable
private fun ModelSpoofValuesRow() {
    var editing by remember { mutableStateOf(false) }
    // Display state that refreshes right after the dialog commits, so the summary reflects the
    // edited model/device without leaving the screen.
    var displayModel by remember {
        mutableStateOf(
            Preferences.getString(
                Preferences.KEY_PA_MODEL_SPOOF_MODEL,
                Preferences.DEFAULT_PA_MODEL_SPOOF_MODEL
            )
        )
    }
    var displayDevice by remember {
        mutableStateOf(
            Preferences.getString(
                Preferences.KEY_PA_MODEL_SPOOF_DEVICE,
                Preferences.DEFAULT_PA_MODEL_SPOOF_DEVICE
            )
        )
    }
    var modelInput by remember { mutableStateOf(displayModel) }
    var deviceInput by remember { mutableStateOf(displayDevice) }

    ArrowPreference(
        title = stringResource(R.string.settings_pa_model_spoof_values_title),
        summary = stringResource(R.string.settings_pa_model_spoof_values_summary, displayModel, displayDevice),
        onClick = {
            modelInput = displayModel
            deviceInput = displayDevice
            editing = true
        }
    )
    OverlayDialog(
        show = editing,
        title = stringResource(R.string.settings_pa_model_spoof_values_title),
        summary = stringResource(R.string.settings_pa_model_spoof_dialog_summary),
        onDismissRequest = { editing = false },
        content = {
            TextField(
                modifier = Modifier.padding(bottom = 8.dp),
                value = modelInput,
                maxLines = 1,
                label = stringResource(R.string.settings_pa_model_spoof_model_label),
                onValueChange = { modelInput = it.trim() }
            )
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = deviceInput,
                maxLines = 1,
                label = stringResource(R.string.settings_pa_model_spoof_device_label),
                onValueChange = { deviceInput = it.trim() }
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.scale_cancel),
                    onClick = { editing = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.scale_ok),
                    onClick = {
                        Preferences.putString(Preferences.KEY_PA_MODEL_SPOOF_MODEL, modelInput)
                        Preferences.putString(Preferences.KEY_PA_MODEL_SPOOF_DEVICE, deviceInput)
                        displayModel = modelInput
                        displayDevice = deviceInput
                        editing = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}
