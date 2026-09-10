package com.takekazex.hypertweak.ui.page

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
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

@Composable
fun SettingsScreenContent(
    padding: PaddingValues,
    showInSettings: Boolean,
    onShowInSettingsChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    onNavigateToScopePrompts: () -> Unit,
    ccEditEnabled: Boolean,
    onCcEditEnabledChange: (Boolean) -> Unit,
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
    themeSummary: String,
    onNavigateToAppearance: () -> Unit,
    allowLandscape: Boolean,
    onAllowLandscapeChange: (Boolean) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
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
                    ArrowPreference(
                        title = stringResource(R.string.settings_scope_prompts),
                        summary = stringResource(R.string.settings_scope_prompts_summary),
                        onClick = onNavigateToScopePrompts
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
                    // AON visual-perception / air-gesture unlocks: reveal Settings entries that the
                    // device hides behind `config_aon_*` resource gates (see VisualPerceptionSettingsHooker).
                    // These act on the next Settings UI refresh in the same process; the toggles force the
                    // Settings-side capability checks only — runtime sensor gates in system_server are
                    // separate (see docs/FEATURE_DETAIL.md).
                    var unlockVisual by remember { mutableStateOf(Preferences.unlockMoreVisualPerception()) }
                    var unlockGestures by remember { mutableStateOf(Preferences.unlockMoreAonGestures()) }
                    SwitchPreference(
                        checked = unlockVisual,
                        onCheckedChange = {
                            unlockVisual = it
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_MORE_VISUAL_PERCEPTION, it)
                        },
                        title = stringResource(R.string.settings_unlock_visual_perception_title),
                        summary = stringResource(R.string.settings_unlock_visual_perception_summary)
                    )
                    SwitchPreference(
                        checked = unlockGestures,
                        onCheckedChange = {
                            unlockGestures = it
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_MORE_AON_GESTURES, it)
                        },
                        title = stringResource(R.string.settings_unlock_aon_gestures_title),
                        summary = stringResource(R.string.settings_unlock_aon_gestures_summary)
                    )
                    // 自适应刷新率Pro (Mimotion PWM): reveal the 显示与亮度 row that HyperOS removes
                    // when `ro.display.enable_pwm_switch` is unset, and let system_server re-apply the
                    // saved mode at boot. Settings-side reveal needs a fresh Settings process; the
                    // runtime re-apply needs a reboot; extra gears still depend on panel/DF support.
                    var unlockAdaptiveRefresh by remember { mutableStateOf(Preferences.unlockAdaptiveRefreshPro()) }
                    SwitchPreference(
                        checked = unlockAdaptiveRefresh,
                        onCheckedChange = {
                            unlockAdaptiveRefresh = it
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_ADAPTIVE_REFRESH_PRO, it)
                        },
                        title = stringResource(R.string.settings_unlock_adaptive_refresh_title),
                        summary = stringResource(R.string.settings_unlock_adaptive_refresh_summary)
                    )
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

            // Other
            SmallTitle(text = stringResource(R.string.settings_other))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
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
