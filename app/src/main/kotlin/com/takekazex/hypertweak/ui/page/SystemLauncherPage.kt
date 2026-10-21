package com.takekazex.hypertweak.ui.page

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
import com.takekazex.hypertweak.hook.NativeRuleConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.PlatformLevel
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
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
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val handleRestartedScopes = LocalRestartScopeHandled.current
    var hideRecentsClearButton by rememberSaveable { mutableStateOf(Preferences.hideRecentsClearButton()) }
    var folderStack by rememberSaveable { mutableStateOf(Preferences.folderStackPreview()) }
    var recentsHaptic by rememberSaveable { mutableStateOf(Preferences.recentsCardHaptic()) }
    var assistantWidgets by rememberSaveable { mutableStateOf(Preferences.allowAndroidWidgetsToAssistant()) }
    var openedFolderColumns by rememberSaveable { mutableIntStateOf(Preferences.openedFolderColumns()) }
    var backGestureEnabled by rememberSaveable { mutableStateOf(Preferences.backGestureEnabled()) }
    var hyperosIndicator by rememberSaveable { mutableStateOf(Preferences.getBoolean(Preferences.KEY_BACK_GESTURE_HYPEROS_INDICATOR, false)) }
    var hyperosHaptics by rememberSaveable { mutableStateOf(Preferences.getBoolean(Preferences.KEY_BACK_GESTURE_HYPEROS_HAPTICS, false)) }
    var enhancedHaptics by rememberSaveable { mutableStateOf(Preferences.getBoolean(Preferences.KEY_BACK_GESTURE_ENHANCED_HAPTICS, false)) }
    var slideAnimation by rememberSaveable { mutableStateOf(Preferences.getBoolean(Preferences.KEY_BACK_GESTURE_SLIDE_ANIMATION, false)) }
    var crossActivityWallpaper by rememberSaveable { mutableStateOf(Preferences.getBoolean(Preferences.KEY_BACK_GESTURE_CROSS_ACTIVITY_WALLPAPER, false)) }
    var crossTaskWallpaper by rememberSaveable { mutableStateOf(Preferences.getBoolean(Preferences.KEY_BACK_GESTURE_CROSS_TASK_WALLPAPER, false)) }
    var fullscreenDirectBack by rememberSaveable {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_FULLSCREEN_DIRECT_BACK, false))
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

            // Preserve the launcher controls' original platform gates.
            SmallTitle(stringResource(R.string.settings_launcher_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = hideRecentsClearButton,
                        onCheckedChange = {
                            hideRecentsClearButton = it
                            Preferences.putBoolean(Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON, it)
                            // Preferences delivers the live snapshot to the launcher; keep the
                            // legacy file as an inspectable diagnostic copy.
                            NativeRuleConfig.publish(context, it, openedFolderColumns)
                        },
                        title = stringResource(R.string.settings_recents_clear_button_title),
                        summary = stringResource(R.string.settings_recents_clear_button_summary)
                    )
                    if (PlatformLevel.isOs4) {
                        SwitchPreference(
                            checked = recentsHaptic,
                            onCheckedChange = {
                                recentsHaptic = it
                                Preferences.putBoolean(Preferences.KEY_RECENTS_CARD_HAPTIC, it)
                                NativeRuleConfig.publish(context, hideRecentsClearButton, openedFolderColumns, recentsHaptic = it)
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
                                Preferences.putBoolean(Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, it)
                                NativeRuleConfig.publish(context, hideRecentsClearButton, openedFolderColumns,
                                    assistantWidgets = it)
                            },
                            title = stringResource(R.string.settings_assistant_android_widgets_title),
                            summary = stringResource(R.string.settings_assistant_android_widgets_summary)
                        )
                        SwitchPreference(
                            checked = folderStack,
                            onCheckedChange = {
                                folderStack = it
                                Preferences.putBoolean(Preferences.KEY_FOLDER_STACK_PREVIEW, it)
                                NativeRuleConfig.publish(context, hideRecentsClearButton, openedFolderColumns, folderStack = it)
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
                                openedFolderColumns - Preferences.DEFAULT_OPENED_FOLDER_COLUMNS
                            ).coerceIn(0, 2),
                            onSelectedIndexChange = { index ->
                                openedFolderColumns =
                                    (Preferences.DEFAULT_OPENED_FOLDER_COLUMNS + index).coerceIn(
                                        Preferences.MIN_OPENED_FOLDER_COLUMNS,
                                        Preferences.MAX_OPENED_FOLDER_COLUMNS
                                    )
                                Preferences.putInt(
                                    Preferences.KEY_OPENED_FOLDER_COLUMNS,
                                    openedFolderColumns
                                )
                                NativeRuleConfig.publish(
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
                                Preferences.flush()
                                RestartUtils.restartScope(
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
                            summary = stringResource(R.string.back_gesture_enabled_summary),
                            checked = backGestureEnabled,
                            onCheckedChange = {
                                backGestureEnabled = it
                                Preferences.putBoolean(Preferences.KEY_BACK_GESTURE_ENABLED, it)
                            }
                        )
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
                            enabled = backGestureEnabled,
                            onSelectedIndexChange = {
                                fullscreenDirectBack = it == 1
                                Preferences.putBoolean(Preferences.KEY_FULLSCREEN_DIRECT_BACK, fullscreenDirectBack)
                            }
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.back_indicator_style_title),
                            items = listOf("AOSP", "HyperOS"),
                            selectedIndex = if (hyperosIndicator) 1 else 0,
                            enabled = backGestureEnabled,
                            onSelectedIndexChange = {
                                hyperosIndicator = it == 1
                                Preferences.putBoolean(Preferences.KEY_BACK_GESTURE_HYPEROS_INDICATOR, hyperosIndicator)
                            }
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.haptic_feedback_effect_title),
                            items = listOf("AOSP", "HyperOS"),
                            selectedIndex = if (hyperosHaptics) 1 else 0,
                            enabled = backGestureEnabled,
                            onSelectedIndexChange = {
                                hyperosHaptics = it == 1
                                Preferences.putBoolean(Preferences.KEY_BACK_GESTURE_HYPEROS_HAPTICS, hyperosHaptics)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.hyperos_haptics_enhanced_title),
                            summary = stringResource(if (hyperosHaptics) R.string.hyperos_haptics_enhanced_summary else R.string.hyperos_haptics_enhanced_disabled_summary),
                            checked = enhancedHaptics,
                            enabled = backGestureEnabled && hyperosHaptics,
                            onCheckedChange = {
                                enhancedHaptics = it
                                Preferences.putBoolean(Preferences.KEY_BACK_GESTURE_ENHANCED_HAPTICS, it)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.hyperos_slide_animation_title),
                            summary = stringResource(R.string.hyperos_slide_animation_summary),
                            checked = slideAnimation,
                            enabled = backGestureEnabled,
                            onCheckedChange = {
                                slideAnimation = it
                                Preferences.putBoolean(Preferences.KEY_BACK_GESTURE_SLIDE_ANIMATION, it)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.back_cross_activity_wallpaper_title),
                            summary = stringResource(R.string.back_cross_activity_wallpaper_summary),
                            checked = crossActivityWallpaper,
                            enabled = backGestureEnabled,
                            onCheckedChange = {
                                crossActivityWallpaper = it
                                Preferences.putBoolean(Preferences.KEY_BACK_GESTURE_CROSS_ACTIVITY_WALLPAPER, it)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.back_cross_task_wallpaper_title),
                            summary = stringResource(R.string.back_cross_task_wallpaper_summary),
                            checked = crossTaskWallpaper,
                            enabled = backGestureEnabled,
                            onCheckedChange = {
                                crossTaskWallpaper = it
                                Preferences.putBoolean(Preferences.KEY_BACK_GESTURE_CROSS_TASK_WALLPAPER, it)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
