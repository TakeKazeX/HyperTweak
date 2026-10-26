package com.takekazex.hypertweak.ui.page.launcher

import com.takekazex.hypertweak.ui.components.dialog.LocalRestartScopeHandled
import com.takekazex.hypertweak.ui.components.dialog.restartInProgress
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.settings.contract.BackGestureKeys

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.util.platform.PlatformLevel
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Launcher controls and predictive back gestures (Features → System Launcher). */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun SystemLauncherPage(onBack: () -> Unit, onNavigateToDockBackground: () -> Unit) {
    val services = LocalAppServices.current
    val settingsStore = LocalAppSettingsStore.current
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val upstreamInstalled = com.takekazex.hypertweak.ui.effect.rememberUpstreamBackGestureInstalled()
    val backOptionsAvailable = upstreamInstalled == false
    val upstreamOpenFailed = stringResource(R.string.back_gesture_upstream_open_failed)
    val coroutineScope = rememberCoroutineScope()
    val handleRestartedScopes = LocalRestartScopeHandled.current
    var hideRecentsClearButton by rememberSaveable { mutableStateOf(settingsStore.launcher.hideRecentsClearButton()) }
    var xiaoaiOptions by rememberSaveable { mutableStateOf(settingsStore.launcher.xiaoaiGestureOptions()) }
    var widgetStack by rememberSaveable { mutableStateOf(settingsStore.launcher.androidWidgetStack()) }
    var folderStack by rememberSaveable { mutableStateOf(settingsStore.launcher.folderStackPreview()) }
    var recentsHaptic by rememberSaveable { mutableStateOf(settingsStore.launcher.recentsCardHaptic()) }
    var assistantWidgets by rememberSaveable { mutableStateOf(settingsStore.launcher.allowAndroidWidgetsToAssistant()) }
    var openedFolderColumns by rememberSaveable { mutableIntStateOf(settingsStore.launcher.openedFolderColumns()) }
    var backGestureEnabled by rememberSaveable { mutableStateOf(settingsStore.backGesture.backGestureBoolean(BackGestureKeys.KEY_BACK_GESTURE_ENABLED, false)) }
    var hyperosIndicator by rememberSaveable { mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_INDICATOR, false)) }
    var indicatorBlur by rememberSaveable { mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_INDICATOR_BLUR, false)) }
    var hapticsDisabled by rememberSaveable { mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_HAPTICS_DISABLED, false)) }
    var hyperosHaptics by rememberSaveable { mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_HAPTICS, false)) }
    var enhancedHaptics by rememberSaveable { mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_ENHANCED_HAPTICS, false)) }
    var slideAnimation by rememberSaveable { mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_SLIDE_ANIMATION, false)) }
    var crossActivityWallpaper by rememberSaveable { mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_CROSS_ACTIVITY_WALLPAPER, false)) }
    var crossTaskWallpaper by rememberSaveable { mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_BACK_GESTURE_CROSS_TASK_WALLPAPER, false)) }
    var fullscreenDirectBack by rememberSaveable {
        mutableStateOf(settingsStore.getBoolean(BackGestureKeys.KEY_FULLSCREEN_DIRECT_BACK, false))
    }
    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.settings_launcher_section),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.icon_back))
                }
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

            SmallTitle(stringResource(R.string.settings_xiaoai_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = xiaoaiOptions,
                    onCheckedChange = {
                        xiaoaiOptions = it
                        settingsStore.putBoolean(PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, it)
                        services.nativeRules.publish(context, hideRecentsClearButton, openedFolderColumns, xiaoaiOptions = it)
                    },
                    title = stringResource(R.string.settings_xiaoai_gesture_options_title),
                    summary = stringResource(R.string.settings_xiaoai_gesture_options_summary)
                )
            }

            // Preserve the launcher controls' original platform gates.
            SmallTitle(stringResource(R.string.settings_launcher_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = hideRecentsClearButton,
                        onCheckedChange = {
                            hideRecentsClearButton = it
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, it)
                            // Preferences delivers the live snapshot to the launcher; keep the
                            // legacy file as an inspectable diagnostic copy.
                            services.nativeRules.publish(context, it, openedFolderColumns)
                        },
                        title = stringResource(R.string.settings_recents_clear_button_title),
                        summary = stringResource(R.string.settings_recents_clear_button_summary)
                    )
                    if (PlatformLevel.isOs4) {
                        SwitchPreference(
                            checked = recentsHaptic,
                            onCheckedChange = {
                                recentsHaptic = it
                                settingsStore.putBoolean(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, it)
                                services.nativeRules.publish(context, hideRecentsClearButton, openedFolderColumns, recentsHaptic = it)
                            },
                            title = stringResource(R.string.settings_recents_card_haptic_title),
                            summary = stringResource(R.string.settings_recents_card_haptic_summary)
                        )
                        ArrowPreference(title = stringResource(R.string.dock_title),
                            summary = stringResource(R.string.dock_summary), onClick = onNavigateToDockBackground)
                        SwitchPreference(
                            checked = assistantWidgets,
                            onCheckedChange = {
                                assistantWidgets = it
                                settingsStore.putBoolean(PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, it)
                                services.nativeRules.publish(context, hideRecentsClearButton, openedFolderColumns,
                                    assistantWidgets = it)
                            },
                            title = stringResource(R.string.settings_assistant_android_widgets_title),
                            summary = stringResource(R.string.settings_assistant_android_widgets_summary)
                        )
                        SwitchPreference(
                            checked = widgetStack,
                            onCheckedChange = {
                                widgetStack = it
                                settingsStore.putBoolean(PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, it)
                                services.nativeRules.publish(context, hideRecentsClearButton, openedFolderColumns, widgetStack = it)
                            },
                            title = stringResource(R.string.settings_android_widget_stack_title),
                            summary = stringResource(R.string.settings_android_widget_stack_summary)
                        )
                        SwitchPreference(
                            checked = folderStack,
                            onCheckedChange = {
                                folderStack = it
                                settingsStore.putBoolean(PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, it)
                                services.nativeRules.publish(context, hideRecentsClearButton, openedFolderColumns, folderStack = it)
                            },
                            title = stringResource(R.string.settings_folder_stack_preview_title),
                            summary = stringResource(R.string.settings_folder_stack_preview_summary)
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_opened_folder_columns_title),
                            summary = stringResource(R.string.settings_opened_folder_columns_summary),
                            items = listOf(
                                stringResource(R.string.settings_opened_folder_columns_default),
                                stringResource(R.string.settings_opened_folder_columns_four),
                                stringResource(R.string.settings_opened_folder_columns_five)
                            ),
                            selectedIndex = (
                                openedFolderColumns - PlatformSettingsKeys.DEFAULT_OPENED_FOLDER_COLUMNS
                            ).coerceIn(0, 2),
                            onSelectedIndexChange = { index ->
                                openedFolderColumns =
                                    (PlatformSettingsKeys.DEFAULT_OPENED_FOLDER_COLUMNS + index).coerceIn(
                                        PlatformSettingsKeys.MIN_OPENED_FOLDER_COLUMNS,
                                        PlatformSettingsKeys.MAX_OPENED_FOLDER_COLUMNS
                                    )
                                settingsStore.putInt(
                                    PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS,
                                    openedFolderColumns
                                )
                                services.nativeRules.publish(
                                    context,
                                    hideRecentsClearButton,
                                    openedFolderColumns
                                )
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.settings_opened_folder_restart_title),
                            summary = stringResource(R.string.settings_opened_folder_restart_summary),
                            enabled = !restartInProgress(RestartScopeSelection(miuiHome = true)),
                            onClick = {
                                settingsStore.flush()
                                services.restarts.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(miuiHome = true),
                                    onCompleted = { completed ->
                                        handleRestartedScopes(completed)
                                    },
                                )
                            }
                        )
                    }
                }
            }

            if (PlatformLevel.isOs4) {
                SmallTitle(stringResource(R.string.back_gesture_section))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = stringResource(R.string.back_gesture_enabled_title),
                            summary = stringResource(if (upstreamInstalled == true)
                                R.string.back_gesture_upstream_summary else R.string.back_gesture_enabled_summary),
                            checked = backGestureEnabled,
                            enabled = backOptionsAvailable,
                            onCheckedChange = {
                                backGestureEnabled = it
                                settingsStore.putBoolean(BackGestureKeys.KEY_BACK_GESTURE_ENABLED, it)
                            }
                        )
                        if (upstreamInstalled == true) {
                            ArrowPreference(
                                title = stringResource(R.string.back_gesture_open_upstream),
                                summary = stringResource(R.string.back_gesture_open_upstream_summary),
                                onClick = {
                                    if (!com.takekazex.hypertweak.util.platform.UpstreamBackGesture.openSettings(context)) {
                                        android.widget.Toast.makeText(context, upstreamOpenFailed, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        OverlayDropdownPreference(
                            title = stringResource(R.string.fullscreen_back_behavior_title),
                            summary = stringResource(
                                if (fullscreenDirectBack) R.string.fullscreen_back_direct_summary
                                else R.string.fullscreen_back_show_bars_summary
                            ),
                            items = listOf(
                                stringResource(R.string.fullscreen_back_show_bars),
                                stringResource(R.string.fullscreen_back_direct)
                            ),
                            selectedIndex = if (fullscreenDirectBack) 1 else 0,
                            enabled = backOptionsAvailable && backGestureEnabled,
                            onSelectedIndexChange = {
                                fullscreenDirectBack = it == 1
                                settingsStore.putBoolean(BackGestureKeys.KEY_FULLSCREEN_DIRECT_BACK, fullscreenDirectBack)
                            }
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.back_indicator_style_title),
                            items = listOf("AOSP", "HyperOS", stringResource(R.string.back_indicator_material_title)),
                            selectedIndex = if (!hyperosIndicator) 0 else if (indicatorBlur) 2 else 1,
                            enabled = backOptionsAvailable && backGestureEnabled,
                            onSelectedIndexChange = {
                                hyperosIndicator = it != 0
                                indicatorBlur = it == 2
                                settingsStore.backGesture.putBackGestureAppearance(mapOf(
                                    BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_INDICATOR to hyperosIndicator,
                                    BackGestureKeys.KEY_BACK_GESTURE_INDICATOR_BLUR to indicatorBlur
                                ))
                            }
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.haptic_feedback_effect_title),
                            items = listOf("AOSP", "HyperOS", stringResource(R.string.back_gesture_haptics_none)),
                            selectedIndex = if (hapticsDisabled) 2 else if (hyperosHaptics) 1 else 0,
                            enabled = backOptionsAvailable && backGestureEnabled,
                            onSelectedIndexChange = {
                                hapticsDisabled = it == 2
                                if (!hapticsDisabled) hyperosHaptics = it == 1
                                settingsStore.backGesture.putBackGestureAppearance(mapOf(
                                    BackGestureKeys.KEY_BACK_GESTURE_HAPTICS_DISABLED to hapticsDisabled,
                                    BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_HAPTICS to hyperosHaptics
                                ))
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.hyperos_haptics_enhanced_title),
                            summary = stringResource(if (hyperosHaptics) R.string.hyperos_haptics_enhanced_summary else R.string.hyperos_haptics_enhanced_disabled_summary),
                            checked = enhancedHaptics,
                            enabled = backOptionsAvailable && backGestureEnabled && hyperosHaptics && !hapticsDisabled,
                            onCheckedChange = {
                                enhancedHaptics = it
                                settingsStore.putBoolean(BackGestureKeys.KEY_BACK_GESTURE_ENHANCED_HAPTICS, it)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.hyperos_slide_animation_title),
                            summary = stringResource(R.string.hyperos_slide_animation_summary),
                            checked = slideAnimation,
                            enabled = backOptionsAvailable && backGestureEnabled,
                            onCheckedChange = {
                                slideAnimation = it
                                settingsStore.putBoolean(BackGestureKeys.KEY_BACK_GESTURE_SLIDE_ANIMATION, it)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.back_cross_activity_wallpaper_title),
                            summary = stringResource(R.string.back_cross_activity_wallpaper_summary),
                            checked = crossActivityWallpaper,
                            enabled = backOptionsAvailable && backGestureEnabled,
                            onCheckedChange = {
                                crossActivityWallpaper = it
                                settingsStore.putBoolean(BackGestureKeys.KEY_BACK_GESTURE_CROSS_ACTIVITY_WALLPAPER, it)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.back_cross_task_wallpaper_title),
                            summary = stringResource(R.string.back_cross_task_wallpaper_summary),
                            checked = crossTaskWallpaper,
                            enabled = backOptionsAvailable && backGestureEnabled,
                            onCheckedChange = {
                                crossTaskWallpaper = it
                                settingsStore.putBoolean(BackGestureKeys.KEY_BACK_GESTURE_CROSS_TASK_WALLPAPER, it)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
