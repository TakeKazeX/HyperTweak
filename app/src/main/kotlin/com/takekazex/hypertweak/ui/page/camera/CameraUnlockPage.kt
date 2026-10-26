package com.takekazex.hypertweak.ui.page.camera

import com.takekazex.hypertweak.ui.components.dialog.LocalRestartScopeRequest
import com.takekazex.hypertweak.settings.contract.CameraSettingsKeys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.CameraLegendaryMomentMode
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Camera app (`com.android.camera`) feature unlocks.
 *
 * The camera normally runs its native device config. Leica Moment only opens its mode entry;
 * Legendary Moment may select the camera's Madrid config when packaged. Extra mode switches
 * target their own entries. The on-picture watermark and EXIF keep this device's own brand + model.
 *
 * Most switches need a camera app restart (the hooks are installed on attach); toggling
 * them afterwards is live unless a summary says otherwise.
 */

/**
 * Camera-unlock controls without any page chrome, so the combined camera/watermark page can host
 * them inside its own TabRow. [topPadding] / [bottomPadding] are supplied by the host so the body
 * clears the status bar and the navigation bar without a nested Scaffold.
 */
@Composable
fun CameraUnlockContent(
    modifier: Modifier,
    topPadding: Dp,
    bottomPadding: Dp
) {
    val settingsStore = LocalAppSettingsStore.current
    val requestRestartScopes = LocalRestartScopeRequest.current

    fun requestCameraRestart() {
        requestRestartScopes(
            RestartScopeSelection(
                additionalPackages = setOf(RestartScopeSelection.PACKAGE_CAMERA)
            )
        )
    }

    var ignoreDeviceMismatch by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_IGNORE_DEVICE_MISMATCH, false))
    }
    var leicaStyle by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_LEICA_STYLE, false))
    }
    var allShutterSounds by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_ALL_SHUTTER_SOUNDS, false))
    }
    var ultraHdQuality by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_ULTRA_HD_QUALITY, false))
    }
    var selfieSettings by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_SELFIE_SETTINGS, false))
    }
    var legendaryMomentMode by remember {
        mutableStateOf(settingsStore.camera.cameraLegendaryMomentMode())
    }
    var legendaryExtraModes by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_EXTRA_MODES, false))
    }
    var smartComposition by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_SMART_COMPOSITION, false))
    }
    var contentCredential by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_CONTENT_CREDENTIAL, false))
    }
    var adaptiveLens by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_ADAPTIVE_LENS, false))
    }
    var masterLiveEnable by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_ENABLE, false))
    }
    var mlRedCarpet by remember {
        mutableStateOf(
            settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_RED_CARPET, false)
        )
    }
    var mlFullFocal by remember {
        mutableStateOf(
            settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_FULL_FOCAL, false)
        )
    }
    var mlVideoSizeProbe by remember {
        mutableStateOf(
            settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE, false)
        )
    }
    var themeLcc by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_IMPERSONATE_THEME_LCC, false))
    }
    var customWm by remember {
        mutableStateOf(settingsStore.getBoolean(CameraSettingsKeys.KEY_CAMERA_WM_CUSTOM, false))
    }
    var customBrand by remember {
        mutableStateOf(settingsStore.getString(CameraSettingsKeys.KEY_CAMERA_WM_CUSTOM_BRAND))
    }
    var customModel by remember {
        mutableStateOf(settingsStore.getString(CameraSettingsKeys.KEY_CAMERA_WM_CUSTOM_MODEL))
    }
    var editingBrand by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf(false) }

    fun set(key: String, value: Boolean, needsRestart: Boolean = false) {
        if (needsRestart) requestCameraRestart()
        settingsStore.putBoolean(key, value)
    }

    Column(modifier = modifier) {
        Spacer(Modifier.height(topPadding + 8.dp))

        SmallTitle(stringResource(R.string.camera_unlock_features))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = ignoreDeviceMismatch,
                    onCheckedChange = { enabled ->
                        ignoreDeviceMismatch = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_IGNORE_DEVICE_MISMATCH, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_ignore_mismatch_title),
                    summary = stringResource(R.string.camera_unlock_ignore_mismatch_summary)
                )
                SwitchPreference(
                    checked = leicaStyle,
                    onCheckedChange = { enabled ->
                        leicaStyle = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_LEICA_STYLE, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_leica_style_title),
                    summary = stringResource(R.string.camera_unlock_leica_style_summary)
                )
                SwitchPreference(
                    checked = allShutterSounds,
                    onCheckedChange = { enabled ->
                        allShutterSounds = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_ALL_SHUTTER_SOUNDS, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_all_shutter_sounds_title),
                    summary = stringResource(R.string.camera_unlock_all_shutter_sounds_summary)
                )
                SwitchPreference(
                    checked = ultraHdQuality,
                    onCheckedChange = { enabled ->
                        ultraHdQuality = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_ULTRA_HD_QUALITY, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_ultra_hd_title),
                    summary = stringResource(R.string.camera_unlock_ultra_hd_summary)
                )
                SwitchPreference(
                    checked = selfieSettings,
                    onCheckedChange = { enabled ->
                        selfieSettings = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_SELFIE_SETTINGS, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_selfie_settings_title),
                    summary = stringResource(R.string.camera_unlock_selfie_settings_summary)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.camera_unlock_legendary_moment_title),
                    summary = stringResource(R.string.camera_unlock_legendary_moment_summary),
                    items = listOf(
                        stringResource(R.string.camera_unlock_legendary_moment_off),
                        stringResource(R.string.camera_unlock_legendary_moment_leica),
                        stringResource(R.string.camera_unlock_legendary_moment_legendary)
                    ),
                    selectedIndex = CameraLegendaryMomentMode.index(legendaryMomentMode),
                    onSelectedIndexChange = { index ->
                        val mode = CameraLegendaryMomentMode.fromIndex(index)
                        legendaryMomentMode = mode
                        requestCameraRestart()
                        settingsStore.camera.setCameraLegendaryMomentMode(mode)
                    },
                )
                SwitchPreference(
                    checked = legendaryExtraModes,
                    onCheckedChange = { enabled ->
                        legendaryExtraModes = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_EXTRA_MODES, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_legendary_extra_modes_title),
                    summary = stringResource(R.string.camera_unlock_legendary_extra_modes_summary)
                )
                SwitchPreference(
                    checked = smartComposition,
                    onCheckedChange = { enabled ->
                        smartComposition = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_SMART_COMPOSITION, enabled)
                    },
                    title = stringResource(R.string.camera_unlock_smart_composition_title),
                    summary = stringResource(R.string.camera_unlock_smart_composition_summary)
                )
                SwitchPreference(
                    checked = contentCredential,
                    onCheckedChange = { enabled ->
                        contentCredential = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_CONTENT_CREDENTIAL, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_content_credential_title),
                    summary = stringResource(R.string.camera_unlock_content_credential_summary)
                )
                SwitchPreference(
                    checked = adaptiveLens,
                    onCheckedChange = { enabled ->
                        adaptiveLens = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_ADAPTIVE_LENS, enabled)
                    },
                    title = stringResource(R.string.camera_unlock_adaptive_lens_title),
                    summary = stringResource(R.string.camera_unlock_adaptive_lens_summary)
                )
                SwitchPreference(
                    checked = masterLiveEnable,
                    onCheckedChange = { enabled ->
                        masterLiveEnable = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_ENABLE, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_masterlive_title),
                    summary = stringResource(R.string.camera_unlock_masterlive_summary)
                )
                SwitchPreference(
                    checked = mlRedCarpet,
                    onCheckedChange = { enabled ->
                        mlRedCarpet = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_RED_CARPET, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_red_carpet_title),
                    summary = stringResource(R.string.camera_unlock_red_carpet_summary)
                )
                SwitchPreference(
                    checked = mlFullFocal,
                    onCheckedChange = { enabled ->
                        mlFullFocal = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_FULL_FOCAL, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_full_focal_title),
                    summary = stringResource(R.string.camera_unlock_full_focal_summary)
                )
                SwitchPreference(
                    checked = mlVideoSizeProbe,
                    onCheckedChange = { enabled ->
                        mlVideoSizeProbe = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_video_size_probe_title),
                    summary = stringResource(R.string.camera_unlock_video_size_probe_summary)
                )
                SwitchPreference(
                    checked = themeLcc,
                    onCheckedChange = { enabled ->
                        themeLcc = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_IMPERSONATE_THEME_LCC, enabled)
                    },
                    title = stringResource(R.string.camera_unlock_theme_lcc_title),
                    summary = stringResource(R.string.camera_unlock_theme_lcc_summary)
                )
                SwitchPreference(
                    checked = customWm,
                    onCheckedChange = { enabled ->
                        customWm = enabled
                        set(CameraSettingsKeys.KEY_CAMERA_WM_CUSTOM, enabled)
                        if (!enabled) {
                            editingBrand = false
                            editingModel = false
                        }
                    },
                    title = stringResource(R.string.camera_unlock_custom_title),
                    summary = stringResource(R.string.camera_unlock_custom_summary)
                )
                if (customWm) {
                    ArrowPreference(
                        title = stringResource(R.string.camera_unlock_custom_brand_title),
                        summary = if (customBrand.isEmpty()) {
                            stringResource(R.string.camera_unlock_custom_unset)
                        } else {
                            customBrand
                        },
                        onClick = { editingBrand = true },
                        holdDownState = editingBrand
                    )
                    ArrowPreference(
                        title = stringResource(R.string.camera_unlock_custom_model_title),
                        summary = if (customModel.isEmpty()) {
                            stringResource(R.string.camera_unlock_custom_unset)
                        } else {
                            customModel
                        },
                        onClick = { editingModel = true },
                        holdDownState = editingModel
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SmallTitle(stringResource(R.string.camera_unlock_notes))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.camera_unlock_note_scope),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.camera_unlock_note_restart),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.camera_unlock_note_exif),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp
                )
            }
        }
        // Dialogs composed INSIDE the scaffold content (like ScaleDialog in AppearancePage);
        // OverlayDialog positioned outside the Scaffold subtree did not show.
        CameraWatermarkTextDialog(
            show = editingBrand,
            title = stringResource(R.string.camera_unlock_custom_brand_title),
            initial = customBrand,
            onDismissRequest = { editingBrand = false },
            onConfirm = { value ->
                customBrand = value
                settingsStore.putString(CameraSettingsKeys.KEY_CAMERA_WM_CUSTOM_BRAND, value)
                editingBrand = false
            }
        )
        CameraWatermarkTextDialog(
            show = editingModel,
            title = stringResource(R.string.camera_unlock_custom_model_title),
            initial = customModel,
            onDismissRequest = { editingModel = false },
            onConfirm = { value ->
                customModel = value
                settingsStore.putString(CameraSettingsKeys.KEY_CAMERA_WM_CUSTOM_MODEL, value)
                editingModel = false
            }
        )
        Spacer(Modifier.height(bottomPadding + 24.dp))
    }
}

/** Small text-input dialog for the custom watermark brand / model values. */
@Composable
private fun CameraWatermarkTextDialog(
    show: Boolean,
    title: String,
    initial: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = stringResource(R.string.camera_unlock_custom_dialog_summary),
        onDismissRequest = onDismissRequest,
        content = {
            var text by remember(show) { mutableStateOf(initial) }
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = text,
                maxLines = 1,
                onValueChange = { text = it }
            )
            Row {
                TextButton(
                    text = stringResource(R.string.scale_cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.camera_unlock_custom_ok),
                    onClick = { onConfirm(text.trim()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )
}
