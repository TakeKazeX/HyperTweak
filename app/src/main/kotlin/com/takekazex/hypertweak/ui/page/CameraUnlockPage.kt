package com.takekazex.hypertweak.ui.page

import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.CameraStreetMode
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.ScopeManager
import kotlinx.coroutines.launch
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
 * Flagship impersonation for the camera app (`com.android.camera`, MiuiCamera).
 *
 * When the master switch is on, `CameraImpersonationHooker` swaps the per-device capability
 * config for a flagship (`com.mi.device.Nezha`) instance so every capability gate opens on any
 * device. The on-picture watermark is ALWAYS kept on this device's own brand + model
 * (unconditional — "keep model" is not a switch any more); turning the "custom watermark" switch
 * on reveals two text rows to override the brand and the model. The "keep focal lengths" switch
 * (on by default) keeps the zoom/focal line-up (焦段) on this device's own config while every
 * capability boolean still comes from the flagship. The initial enable needs a camera app restart
 * (the hooks are installed on attach); toggling the switches afterwards is live.
 */
@Composable
fun CameraUnlockPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var master by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE, false))
    }
    var themeLcc by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, false))
    }
    var keepFocal by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_KEEP_FOCAL, true))
    }
    var keepImaging by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_KEEP_IMAGING, true))
    }
    var guardModes by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_GUARD_MODES, true))
    }
    var guardCameraId by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_GUARD_CAMERA_ID, false))
    }
    var targetK100ProMax by remember {
        mutableStateOf(
            Preferences.getString(Preferences.KEY_CAMERA_IMPERSONATE_TARGET, "k100promax") ==
                "k100promax"
        )
    }
    var streetMode by remember {
        mutableStateOf(Preferences.cameraStreetMode())
    }
    var leicaStyle by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CAMERA_LEICA_STYLE, true))
    }
    var mlOpModeSafe by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_OPMODE_SAFE, false)
        )
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

    // Hoisted string resources (LocalContextGetResourceValueCall): resolved in composition,
    // referenced from the coroutine below.
    val scopeAdded = stringResource(R.string.watermark_scope_added_camera)
    val scopeDeclined = stringResource(R.string.watermark_scope_declined_camera)
    val scopeFailedFormat = stringResource(R.string.watermark_scope_failed_camera)
    val scopeUnavailable = stringResource(R.string.watermark_scope_unavailable_camera)

    fun set(key: String, value: Boolean) {
        Preferences.putBoolean(key, value)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.camera_unlock_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.camera_unlock_back)) }
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

            SmallTitle(stringResource(R.string.camera_unlock_master))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = master,
                        onCheckedChange = { enabled ->
                            master = enabled
                            set(Preferences.KEY_CAMERA_IMPERSONATE, enabled)
                            if (enabled) {
                                // com.android.camera is a declared scope (scope.list); make
                                // sure LSPosed actually granted it before the camera is restarted.
                                coroutineScope.launch {
                                    when (val result = ScopeManager.request(setOf("com.android.camera"))) {
                                        is ScopeManager.Result.Applied -> Toast.makeText(
                                            context,
                                            scopeAdded,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        ScopeManager.Result.NoChange -> Unit
                                        is ScopeManager.Result.Rejected -> Toast.makeText(
                                            context,
                                            scopeDeclined,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        is ScopeManager.Result.Failed -> Toast.makeText(
                                            context,
                                            String.format(scopeFailedFormat, result.message),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                                            context,
                                            scopeUnavailable,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        title = stringResource(R.string.camera_unlock_master_title),
                        summary = stringResource(R.string.camera_unlock_master_summary)
                    )
                    SwitchPreference(
                        checked = targetK100ProMax,
                        onCheckedChange = { enabled ->
                            targetK100ProMax = enabled
                            Preferences.putString(
                                Preferences.KEY_CAMERA_IMPERSONATE_TARGET,
                                if (enabled) "k100promax" else "nezha"
                            )
                        },
                        title = stringResource(R.string.camera_unlock_target_title),
                        summary = stringResource(R.string.camera_unlock_target_summary)
                    )
                    // 街拍 (mode 225) unlock selector: 新街拍 rides on the impersonated
                    // flagship config; 兼容模式街拍 opens the entry without impersonation.
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
                            Preferences.setCameraStreetMode(mode)
                        }
                    )
                    SwitchPreference(
                        checked = leicaStyle,
                        onCheckedChange = { enabled ->
                            leicaStyle = enabled
                            set(Preferences.KEY_CAMERA_LEICA_STYLE, enabled)
                        },
                        title = stringResource(R.string.camera_unlock_leica_style_title),
                        summary = stringResource(R.string.camera_unlock_leica_style_summary)
                    )
                    SwitchPreference(
                        checked = mlOpModeSafe,
                        onCheckedChange = { enabled ->
                            mlOpModeSafe = enabled
                            set(Preferences.KEY_CAMERA_MASTERLIVE_OPMODE_SAFE, enabled)
                        },
                        title = stringResource(R.string.camera_unlock_opmode_title),
                        summary = stringResource(R.string.camera_unlock_opmode_summary)
                    )
                    SwitchPreference(
                        checked = keepFocal,
                        onCheckedChange = { enabled ->
                            keepFocal = enabled
                            set(Preferences.KEY_CAMERA_KEEP_FOCAL, enabled)
                        },
                        title = stringResource(R.string.camera_unlock_keep_focal_title),
                        summary = stringResource(R.string.camera_unlock_keep_focal_summary)
                    )
                    SwitchPreference(
                        checked = keepImaging,
                        onCheckedChange = { enabled ->
                            keepImaging = enabled
                            set(Preferences.KEY_CAMERA_KEEP_IMAGING, enabled)
                        },
                        title = stringResource(R.string.camera_unlock_keep_imaging_title),
                        summary = stringResource(R.string.camera_unlock_keep_imaging_summary)
                    )
                    SwitchPreference(
                        checked = guardModes,
                        onCheckedChange = { enabled ->
                            guardModes = enabled
                            set(Preferences.KEY_CAMERA_GUARD_MODES, enabled)
                        },
                        title = stringResource(R.string.camera_unlock_guard_modes_title),
                        summary = stringResource(R.string.camera_unlock_guard_modes_summary)
                    )
                    SwitchPreference(
                        checked = guardCameraId,
                        onCheckedChange = { enabled ->
                            guardCameraId = enabled
                            set(Preferences.KEY_CAMERA_GUARD_CAMERA_ID, enabled)
                        },
                        title = stringResource(R.string.camera_unlock_guard_camera_id_title),
                        summary = stringResource(R.string.camera_unlock_guard_camera_id_summary)
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
            Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
        }
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
