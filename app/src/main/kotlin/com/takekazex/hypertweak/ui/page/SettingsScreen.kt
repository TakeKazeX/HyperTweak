package com.takekazex.hypertweak.ui.page

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.getSystemAccentColor
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.LauncherVersion
import com.takekazex.hypertweak.util.PlatformLevel
import com.takekazex.hypertweak.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import com.takekazex.hypertweak.ui.effect.rememberContentReady
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio

@Composable
fun SettingsScreenContent(
    padding: PaddingValues,
    showInSettings: Boolean,
    onShowInSettingsChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    ccEditEnabled: Boolean,
    onCcEditEnabledChange: (Boolean) -> Unit,
    paModelSpoofEnabled: Boolean,
    onPaModelSpoofEnabledChange: (Boolean) -> Unit,
    onNavigateToIconTuner: () -> Unit,
    onNavigateToGlassTuner: () -> Unit,
    onNavigateToWatermark: () -> Unit,
    onNavigateToCameraUnlock: () -> Unit,
    onNavigateToControlCenterCorner: () -> Unit,
    onNavigateToControlCenterResize: () -> Unit,
    launcherMajor: Int,
    launcherSupportsBackRoute: Boolean,
    aospBackMiuiHomeHooks: Boolean,
    onAospBackMiuiHomeHooksChange: (Boolean) -> Unit,
    onNavigateToPredictiveBackApps: () -> Unit,
    onNavigateToAospRestore: () -> Unit,
    themeSummary: String,
    onNavigateToAppearance: () -> Unit,
    allowLandscape: Boolean,
    onAllowLandscapeChange: (Boolean) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
    onNavigateToBatteryInfo: () -> Unit,
    backdrop: LayerBackdrop,
    appLanguage: Int,
    onAppLanguageChange: (Int) -> Unit
) {
    val context = LocalContext.current
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
                title = stringResource(R.string.settings_settings),
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

            SmallTitle(text = stringResource(R.string.settings_appearance))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = stringResource(R.string.settings_appearance),
                    summary = themeSummary,
                    onClick = onNavigateToAppearance
                )
            }

            // Module Preferences
            SmallTitle(text = stringResource(R.string.settings_module_preferences))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = showInSettings,
                        onCheckedChange = onShowInSettingsChange,
                        title = stringResource(R.string.settings_show_entry_in_system_settings),
                        summary = stringResource(R.string.settings_show_entry_in_system_settings_summary)
                    )

                    SwitchPreference(
                        checked = hideLauncherIcon,
                        onCheckedChange = onHideLauncherIconChange,
                        title = stringResource(R.string.settings_hide_desktop_icon),
                        summary = stringResource(R.string.settings_hide_desktop_icon_summary)
                    )

                    AnimatedVisibility(
                        visible = !hideLauncherIcon,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_app_shortcuts),
                            summary = stringResource(R.string.settings_app_shortcuts_summary),
                            onClick = onNavigateToAppShortcuts
                        )
                    }

                    SwitchPreference(
                        checked = allowLandscape,
                        onCheckedChange = onAllowLandscapeChange,
                        title = stringResource(R.string.settings_allow_landscape),
                        summary = stringResource(R.string.settings_allow_landscape_summary)
                    )

                    OverlayDropdownPreference(
                        title = stringResource(id = R.string.pref_language_title),
                        items = listOf(
                            stringResource(id = R.string.pref_language_device_default),
                            stringResource(id = R.string.pref_language_zh_cn),
                            stringResource(id = R.string.pref_language_en)
                        ),
                        selectedIndex = appLanguage,
                        onSelectedIndexChange = onAppLanguageChange
                    )
                }
            }

            SmallTitle(text = stringResource(R.string.settings_experimental))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_icon_tuner),
                        summary = stringResource(R.string.settings_icon_tuner_summary),
                        onClick = onNavigateToIconTuner
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_watermark_unlock),
                        summary = stringResource(R.string.settings_watermark_unlock_summary),
                        onClick = onNavigateToWatermark
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_camera_unlock),
                        summary = stringResource(R.string.settings_camera_unlock_summary),
                        onClick = onNavigateToCameraUnlock
                    )
                    // Device-model spoof for the Smart Assistant: report a 澎湃G1 model/device so
                    // Xiaomi's server pushes the "智能测算" MAML suit (which contains the 精准电量
                    // widget). Needs com.miui.personalassistant in scope; values apply on the next
                    // request, so no restart is required once the hooks are installed.
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
                    // The material style (材质风格) with its two modes only exists on OS4;
                    // OS3 SystemUI has neither the bionics resources nor the material_style key.
                    if (PlatformLevel.isOs4) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_glass_material_tuner),
                            summary = stringResource(R.string.settings_glass_material_tuner_summary),
                            onClick = onNavigateToGlassTuner
                        )
                    }
                    // Control Center custom corner radius. The radius lives in the OS4 control
                    // center plugin classes, so the entry is OS4-only.
                    if (PlatformLevel.isOs4) {
                        ArrowPreference(
                            title = stringResource(R.string.tweaks_cc_corner_enabled_title),
                            summary = stringResource(R.string.tweaks_cc_corner_enabled_summary),
                            onClick = onNavigateToControlCenterCorner
                        )
                    }
                    // Control-center editor cards: the fixed main-panel contents (big cards,
                    // media player, brightness/volume sliders, device center) show up in
                    // 编辑与排序 and become drag-reorderable like the quick actions. The editor
                    // mechanics live in the OS4 plugin classes only.
                    if (PlatformLevel.isOs4) {
                        SwitchPreference(
                            checked = ccEditEnabled,
                            onCheckedChange = onCcEditEnabledChange,
                            title = stringResource(R.string.settings_cc_edit_title),
                            summary = stringResource(R.string.settings_cc_edit_summary)
                        )
                        // Control-center element sizes (big cards, sliders, media player, device
                        // center) plus quick switches rendered as big cards. Same plugin-only scope.
                        ArrowPreference(
                            title = stringResource(R.string.cc_resize_title),
                            summary = stringResource(R.string.cc_resize_enabled_summary),
                            onClick = onNavigateToControlCenterResize
                        )
                    }
                }
            }

            // Launcher-dependent halves of the AOSP back gesture. The gesture itself and its
            // SystemUI-only options stay under Features; only what hooks com.miui.home lives here.
            // Hidden on OS4 together with the AOSP back gesture feature.
            if (!PlatformLevel.isOs4) {
                SmallTitle(text = stringResource(R.string.settings_launcher_hooks))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    SwitchPreference(
                        checked = aospBackMiuiHomeHooks && launcherSupportsBackRoute,
                        onCheckedChange = onAospBackMiuiHomeHooksChange,
                        title = stringResource(R.string.settings_predictive_return_to_home),
                        summary = launcherBackRouteSummary(context, launcherMajor, launcherSupportsBackRoute),
                        enabled = launcherSupportsBackRoute
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_predictive_back_apps),
                        summary = stringResource(R.string.settings_predictive_back_apps_summary),
                        onClick = onNavigateToPredictiveBackApps
                    )
                }
            }

            SmallTitle(text = stringResource(R.string.settings_aosp_restore))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = stringResource(R.string.settings_aosp_restore),
                    summary = stringResource(R.string.settings_aosp_restore_summary),
                    onClick = onNavigateToAospRestore
                )
            }

            // Other
            SmallTitle(text = stringResource(R.string.settings_other))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = stringResource(R.string.battery_info_menu_title),
                    summary = stringResource(R.string.battery_info_menu_summary),
                    onClick = onNavigateToBatteryInfo
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_debug),
                    summary = stringResource(R.string.settings_debug_summary),
                    onClick = onNavigateToDebugLogs
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                    onClick = onNavigateToAbout
                )
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
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

/**
 * The predictive return-home animation hooks `com.miui.home` Java classes that only Launcher 7
 * and older ship, so explain why the switch is unavailable rather than just greying it out.
 */
private fun launcherBackRouteSummary(context: Context, launcherMajor: Int, supported: Boolean): String {
    val version = LauncherVersion.versionName.ifBlank {
        context.getString(R.string.settings_launcher_version_unknown)
    }
    return when {
        // Upstream documents the launcher animation hooks as matched to 7.50.xx. Other 7.x
        // builds move the members it resolves, so show the exact version to compare against.
        supported -> context.getString(R.string.settings_back_route_supported, version)
        launcherMajor > 0 ->
            context.getString(R.string.settings_back_route_unsupported_version, version)
        else -> context.getString(R.string.settings_back_route_unsupported_unknown)
    }
}
