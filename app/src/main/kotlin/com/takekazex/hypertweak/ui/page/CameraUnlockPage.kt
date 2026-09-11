package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.CameraStreetMode
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Camera app (`com.android.camera`) feature unlocks.
 *
 * The flagship config-swap impersonation was removed at the user's request (2026-08-30):
 * the camera always runs its own real device config and every switch below unlocks
 * functionality directly on it — MasterLive / street / Leica style / legendary moment /
 * smart composition / content credentials / adaptive lens / ultra-HD quality, plus the
 * independent fake-LCC-theme switch and the custom-watermark editor. The on-picture
 * watermark and EXIF always carry this device's own brand + model.
 *
 * Most switches need a camera app restart (the hooks are installed on attach); toggling
 * them afterwards is live unless a summary says otherwise.
 */
@Composable
fun CameraUnlockPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.camera_unlock_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.camera_unlock_back)) }
            }
        )
    }) { padding ->
        CameraUnlockContent(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            topPadding = padding.calculateTopPadding(),
            bottomPadding = padding.calculateBottomPadding()
        )
    }
}

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
    val requestRestartScopes = LocalRestartScopeRequest.current

    fun requestCameraRestart() {
        requestRestartScopes(
            RestartScopeSelection(
                additionalPackages = setOf(RestartScopeSelection.PACKAGE_CAMERA)
            )
        )
    }

    var streetMode by remember {
        mutableStateOf(Preferences.cameraStreetMode())
    }
    var streetQuickLaunch by remember {
        mutableStateOf(Preferences.cameraStreetQuickLaunch())
    }
    var leicaStyle by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_LEICA_STYLE, false))
    }
    var ultraHdQuality by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_ULTRA_HD_QUALITY, false))
    }
    var selfieSettings by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_SELFIE_SETTINGS, false))
    }
    var legendaryMoment by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_LEGENDARY_MOMENT, false))
    }
    var smartComposition by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_SMART_COMPOSITION, false))
    }
    var contentCredential by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_CONTENT_CREDENTIAL, false))
    }
    var adaptiveLens by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_ADAPTIVE_LENS, false))
    }
    var masterLiveEnable by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_ENABLE, false))
    }
    var mlRedCarpet by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_RED_CARPET, false)
        )
    }
    var mlFullFocal by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_FULL_FOCAL, false)
        )
    }
    var mlVideoSizeProbe by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE, false)
        )
    }
    var themeLcc by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, false))
    }
    var customWm by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_WM_CUSTOM, false))
    }
    var customBrand by remember {
        mutableStateOf(Preferences.getString(Preferences.KEY_CAMERA_WM_CUSTOM_BRAND))
    }
    var customModel by remember {
        mutableStateOf(Preferences.getString(Preferences.KEY_CAMERA_WM_CUSTOM_MODEL))
    }
    var editingBrand by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf(false) }

    fun set(key: String, value: Boolean, needsRestart: Boolean = false) {
        if (needsRestart) requestCameraRestart()
        Preferences.putBoolean(key, value)
    }

    Column(modifier = modifier) {
        Spacer(Modifier.height(topPadding + 8.dp))

        SmallTitle(stringResource(R.string.camera_unlock_features))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                // 街拍 (mode 225) unlock selector: 新街拍 forces the street-support gate on
                // the real config; 兼容模式街拍 opens the entry via its own module entry.
                // Same dropdown pattern as SettingsScreen's fingerprint-avoidance selector.
                OverlayDropdownPreference(
                    title = stringResource(R.string.camera_unlock_street_title),
                    summary = stringResource(R.string.camera_unlock_street_summary),
                    items = listOf(
                        stringResource(R.string.camera_unlock_street_off),
                        stringResource(R.string.camera_unlock_street_new),
                        stringResource(R.string.camera_unlock_street_compat)
                    ),
                    selectedIndex = CameraStreetMode.index(streetMode),
                    onSelectedIndexChange = { index ->
                        val mode = CameraStreetMode.fromIndex(index)
                        streetMode = mode
                        requestCameraRestart()
                        Preferences.setCameraStreetMode(mode)
                    }
                )
                SwitchPreference(
                    checked = streetQuickLaunch,
                    onCheckedChange = { enabled ->
                        streetQuickLaunch = enabled
                        set(Preferences.KEY_CAMERA_STREET_QUICK_LAUNCH, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_street_quick_launch_title),
                    summary = stringResource(R.string.camera_unlock_street_quick_launch_summary)
                )
                SwitchPreference(
                    checked = leicaStyle,
                    onCheckedChange = { enabled ->
                        leicaStyle = enabled
                        set(Preferences.KEY_CAMERA_LEICA_STYLE, enabled, needsRestart = true)
                        },
                        title = stringResource(R.string.camera_unlock_leica_style_title),
                        summary = stringResource(R.string.camera_unlock_leica_style_summary)
                    )
                    SwitchPreference(
                        checked = ultraHdQuality,
                        onCheckedChange = { enabled ->
                            ultraHdQuality = enabled
                            set(Preferences.KEY_CAMERA_ULTRA_HD_QUALITY, enabled, needsRestart = true)
                        },
                        title = stringResource(R.string.camera_unlock_ultra_hd_title),
                        summary = stringResource(R.string.camera_unlock_ultra_hd_summary)
                    )
                SwitchPreference(
                    checked = selfieSettings,
                    onCheckedChange = { enabled ->
                        selfieSettings = enabled
                        set(Preferences.KEY_CAMERA_SELFIE_SETTINGS, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_selfie_settings_title),
                    summary = stringResource(R.string.camera_unlock_selfie_settings_summary)
                )
                SwitchPreference(
                    checked = legendaryMoment,
                    onCheckedChange = { enabled ->
                        legendaryMoment = enabled
                        set(Preferences.KEY_CAMERA_LEGENDARY_MOMENT, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_legendary_moment_title),
                    summary = stringResource(R.string.camera_unlock_legendary_moment_summary)
                )
                SwitchPreference(
                    checked = smartComposition,
                    onCheckedChange = { enabled ->
                        smartComposition = enabled
                        set(Preferences.KEY_CAMERA_SMART_COMPOSITION, enabled)
                    },
                    title = stringResource(R.string.camera_unlock_smart_composition_title),
                    summary = stringResource(R.string.camera_unlock_smart_composition_summary)
                )
                SwitchPreference(
                    checked = contentCredential,
                    onCheckedChange = { enabled ->
                        contentCredential = enabled
                        set(Preferences.KEY_CAMERA_CONTENT_CREDENTIAL, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_content_credential_title),
                    summary = stringResource(R.string.camera_unlock_content_credential_summary)
                )
                SwitchPreference(
                    checked = adaptiveLens,
                    onCheckedChange = { enabled ->
                        adaptiveLens = enabled
                        set(Preferences.KEY_CAMERA_ADAPTIVE_LENS, enabled)
                    },
                    title = stringResource(R.string.camera_unlock_adaptive_lens_title),
                    summary = stringResource(R.string.camera_unlock_adaptive_lens_summary)
                )
                SwitchPreference(
                    checked = masterLiveEnable,
                    onCheckedChange = { enabled ->
                        masterLiveEnable = enabled
                        set(Preferences.KEY_CAMERA_MASTERLIVE_ENABLE, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_masterlive_title),
                    summary = stringResource(R.string.camera_unlock_masterlive_summary)
                )
                SwitchPreference(
                    checked = mlRedCarpet,
                    onCheckedChange = { enabled ->
                        mlRedCarpet = enabled
                        set(Preferences.KEY_CAMERA_MASTERLIVE_RED_CARPET, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_red_carpet_title),
                    summary = stringResource(R.string.camera_unlock_red_carpet_summary)
                )
                SwitchPreference(
                    checked = mlFullFocal,
                    onCheckedChange = { enabled ->
                        mlFullFocal = enabled
                        set(Preferences.KEY_CAMERA_MASTERLIVE_FULL_FOCAL, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_full_focal_title),
                    summary = stringResource(R.string.camera_unlock_full_focal_summary)
                )
                SwitchPreference(
                    checked = mlVideoSizeProbe,
                    onCheckedChange = { enabled ->
                        mlVideoSizeProbe = enabled
                        set(Preferences.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE, enabled, needsRestart = true)
                    },
                    title = stringResource(R.string.camera_unlock_video_size_probe_title),
                    summary = stringResource(R.string.camera_unlock_video_size_probe_summary)
                )
                SwitchPreference(
                    checked = themeLcc,
                    onCheckedChange = { enabled ->
                        themeLcc = enabled
                        set(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, enabled)
                    },
                    title = stringResource(R.string.camera_unlock_theme_lcc_title),
                    summary = stringResource(R.string.camera_unlock_theme_lcc_summary)
                )
                SwitchPreference(
                    checked = customWm,
                    onCheckedChange = { enabled ->
                        customWm = enabled
                        set(Preferences.KEY_CAMERA_WM_CUSTOM, enabled)
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
                Preferences.putString(Preferences.KEY_CAMERA_WM_CUSTOM_BRAND, value)
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
                Preferences.putString(Preferences.KEY_CAMERA_WM_CUSTOM_MODEL, value)
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
