package com.takekazex.hypertweak.ui.page.home

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppOverlayDialog as OverlayDialog
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun TweaksScreenContent(
    padding: PaddingValues,
    onNavigateToSystemLauncher: () -> Unit,
    onNavigateToSystemUi: () -> Unit,
    onNavigateToSms: () -> Unit,
    onNavigateToScreenshot: () -> Unit,
    onNavigateToDownloadManager: () -> Unit,
    onNavigateToSecurityCenter: () -> Unit,
    onNavigateToAospRestore: () -> Unit,
    onNavigateToSystemFramework: () -> Unit,
    onNavigateToGoogleServices: () -> Unit,
    onNavigateToCameraWatermark: () -> Unit,
    onNavigateToSuperIsland: () -> Unit,
    onNavigateToPurification: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    AppScrollPage(
        title = stringResource(R.string.tweaks_features),
        onBack = null,
        bottomInset = padding.calculateBottomPadding(),
    ) {

        // Keep the pages that operate on a complete system component together, with a visual
        // cue for the app each entry opens.
        AppSection(title = stringResource(R.string.tweaks_system_components_title)) {
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
                title = stringResource(R.string.force_screenshot_section),
                summary = stringResource(R.string.system_framework_summary),
                startAction = {
                    ApplicationIcon(
                        packageName = "android",
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.force_screenshot_section)
                    )
                },
                onClick = onNavigateToSystemFramework
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
                title = stringResource(R.string.sms_title),
                summary = stringResource(R.string.sms_auto_copy_title),
                startAction = {
                    ApplicationIcon(
                        packageName = "com.android.mms",
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.sms_title)
                    )
                },
                onClick = onNavigateToSms
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

        com.takekazex.hypertweak.ui.page.wifi.WifiDetailsControls()

        AppSection(title = stringResource(R.string.purification_title)) {
            ArrowPreference(
                title = stringResource(R.string.purification_title),
                summary = stringResource(R.string.purification_summary),
                onClick = onNavigateToPurification
            )
        }

        AppSection(title = stringResource(R.string.tweaks_personal_assistant_title)) {
            SwitchPreference(
                checked = appSettings.paModelSpoofEnabled,
                onCheckedChange = { appSettings.paModelSpoofEnabled = it },
                title = stringResource(R.string.settings_pa_model_spoof_title),
                summary = stringResource(R.string.settings_pa_model_spoof_summary)
            )
            MiuixExpandableContent(visible = appSettings.paModelSpoofEnabled) {
                ModelSpoofValuesRow()
            }
        }

        // Scope 5: Bluetooth
        AppSection(title = stringResource(R.string.tweaks_bluetooth_title)) {
            SwitchPreference(
                checked = appSettings.disableSpatialAudio,
                onCheckedChange = { appSettings.disableSpatialAudio = it },
                title = stringResource(R.string.tweaks_block_spatial_audio_title),
                summary = stringResource(R.string.tweaks_block_spatial_audio_summary)
            )
            SwitchPreference(
                checked = appSettings.forceAdaptiveAnc,
                onCheckedChange = { appSettings.forceAdaptiveAnc = it },
                title = stringResource(R.string.tweaks_force_adaptive_anc_title),
                summary = stringResource(R.string.tweaks_force_adaptive_anc_summary)
            )
        }

        AppSection(title = stringResource(R.string.tweaks_powerkeeper_title)) {
            SwitchPreference(
                checked = appSettings.powerKeeperBlockWhitelistRestore,
                onCheckedChange = { appSettings.powerKeeperBlockWhitelistRestore = it },
                title = stringResource(R.string.powerkeeper_block_whitelist_restore_title),
                summary = stringResource(R.string.powerkeeper_block_whitelist_restore_summary)
            )
        }

        AppSection(title = stringResource(R.string.tweaks_focus_notification_title)) {
            ArrowPreference(
                title = stringResource(R.string.tweaks_focus_notification_title),
                summary = stringResource(R.string.tweaks_super_island_summary),
                onClick = onNavigateToSuperIsland
            )
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
 * ([ModelSpoofHooker]). Reads and writes [AppSettingsStore] directly, defaulting to the Xiaomi 12S
 * Ultra (`2203121C` / `thor`). Only shown while the master switch is on.
 */
@Composable
private fun ModelSpoofValuesRow() {
    val settingsStore = LocalAppSettingsStore.current
    var editing by remember { mutableStateOf(false) }
    // Display state that refreshes right after the dialog commits, so the summary reflects the
    // edited model/device without leaving the screen.
    var displayModel by remember { mutableStateOf(settingsStore.getString(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_MODEL, PlatformSettingsKeys.DEFAULT_PA_MODEL_SPOOF_MODEL)) }
    var displayDevice by remember { mutableStateOf(settingsStore.getString(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_DEVICE, PlatformSettingsKeys.DEFAULT_PA_MODEL_SPOOF_DEVICE)) }
    val modelInput = remember { TextFieldState(displayModel) }
    val deviceInput = remember { TextFieldState(displayDevice) }

    ArrowPreference(
        title = stringResource(R.string.settings_pa_model_spoof_values_title),
        summary = stringResource(R.string.settings_pa_model_spoof_values_summary, displayModel, displayDevice),
        onClick = {
            modelInput.setTextAndPlaceCursorAtEnd(displayModel)
            deviceInput.setTextAndPlaceCursorAtEnd(displayDevice)
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
                state = modelInput,
                lineLimits = TextFieldLineLimits.SingleLine,
                label = stringResource(R.string.settings_pa_model_spoof_model_label),
                inputTransformation = InputTransformation.byValue { _, proposed -> proposed.trim() },
            )
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                state = deviceInput,
                lineLimits = TextFieldLineLimits.SingleLine,
                label = stringResource(R.string.settings_pa_model_spoof_device_label),
                inputTransformation = InputTransformation.byValue { _, proposed -> proposed.trim() },
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
                        displayModel = modelInput.text.toString()
                        displayDevice = deviceInput.text.toString()
                        settingsStore.putString(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_MODEL, displayModel)
                        settingsStore.putString(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_DEVICE, displayDevice)
                        editing = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}
