package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

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
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import com.takekazex.hypertweak.util.RestartScopeSelection
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun TweaksScreenContent(
    padding: PaddingValues,
    onNavigateToSystemLauncher: () -> Unit,
    onNavigateToSystemUi: () -> Unit,
    onNavigateToScreenshot: () -> Unit,
    onNavigateToDownloadManager: () -> Unit,
    onNavigateToSecurityCenter: () -> Unit,
    onNavigateToAospRestore: () -> Unit,
    onNavigateToGoogleServices: () -> Unit,
    onNavigateToCameraWatermark: () -> Unit,
    paModelSpoofEnabled: Boolean,
    onPaModelSpoofEnabledChange: (Boolean) -> Unit,
    unlockThirdPartyDarkMode: Boolean,
    onUnlockThirdPartyDarkModeChange: (Boolean) -> Unit,
    allowThirdPartyTheme: Boolean,
    onAllowThirdPartyThemeChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,
    onNavigateToSuperIsland: () -> Unit,
    onNavigateToPurification: () -> Unit,
    backdrop: LayerBackdrop
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.tweaks_features),
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
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
                        title = stringResource(R.string.settings_launcher_section),
                        summary = stringResource(R.string.settings_launcher_summary),
                        startAction = {
                            ApplicationIcon(
                                packageName = "com.miui.home",
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.settings_launcher_section)
                            )
                        },
                        onClick = onNavigateToSystemLauncher
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
                        title = stringResource(R.string.screenshot_section),
                        summary = stringResource(R.string.screenshot_page_summary),
                        startAction = {
                            ApplicationIcon(
                                packageName = "com.miui.screenshot",
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.screenshot_section)
                            )
                        },
                        onClick = onNavigateToScreenshot
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
                    // Google-related services live on their own second-level page so this card stays
                    // a list of system components; the row carries the GMS icon like the others.
                    ArrowPreference(
                        title = stringResource(R.string.tweaks_system_core_title),
                        summary = stringResource(R.string.tweaks_system_core_summary),
                        startAction = {
                            ApplicationIcon(
                                packageName = RestartScopeSelection.PACKAGE_GMS,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.tweaks_system_core_title)
                            )
                        },
                        onClick = onNavigateToGoogleServices
                    )
                    // Camera (com.android.camera) and media-editor watermark unlocks share one
                    // second-level page split by a TabRow; the row carries the camera icon.
                    ArrowPreference(
                        title = stringResource(R.string.camera_unlock_title),
                        summary = stringResource(R.string.tweaks_camera_watermark_summary),
                        startAction = {
                            ApplicationIcon(
                                packageName = RestartScopeSelection.PACKAGE_CAMERA,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.camera_unlock_title)
                            )
                        },
                        onClick = onNavigateToCameraWatermark
                    )
                }
            }

            SmallTitle(text = stringResource(R.string.purification_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.purification_title),
                    summary = stringResource(R.string.purification_summary),
                    onClick = onNavigateToPurification
                )
            }

            ForceScreenshotSection()

            SmallTitle(text = stringResource(R.string.tweaks_theme_manager_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = allowThirdPartyTheme,
                    onCheckedChange = onAllowThirdPartyThemeChange,
                    title = stringResource(R.string.tweaks_allow_third_party_theme_title),
                    summary = stringResource(R.string.tweaks_allow_third_party_theme_summary)
                )
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

            SmallTitle(text = stringResource(R.string.tweaks_focus_notification_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.tweaks_focus_notification_title),
                    summary = stringResource(R.string.tweaks_super_island_summary),
                    onClick = onNavigateToSuperIsland
                )
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

/** Loads the target component's launcher icon without blocking the Compose main thread. */
@Composable
internal fun ApplicationIcon(
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
                PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_MODEL,
                PlatformSettingsKeys.DEFAULT_PA_MODEL_SPOOF_MODEL
            )
        )
    }
    var displayDevice by remember {
        mutableStateOf(
            Preferences.getString(
                PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_DEVICE,
                PlatformSettingsKeys.DEFAULT_PA_MODEL_SPOOF_DEVICE
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
                        Preferences.putString(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_MODEL, modelInput)
                        Preferences.putString(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_DEVICE, deviceInput)
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
