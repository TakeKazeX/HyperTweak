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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Experimental switches (Settings → Experimental).
 *
 * Second-level page so the settings tab does not grow into one long scroll. Everything here is
 * still under development, which the header note states outright; the OS4-only entries keep the
 * same platform gates they had when they lived on the settings page.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun ExperimentalFeaturesPage(
    onBack: () -> Unit,
    onNavigateToGlassTuner: () -> Unit,
    onNavigateToControlCenterCorner: () -> Unit,
    onNavigateToControlCenterResize: () -> Unit,
    ccEditEnabled: Boolean,
    onCcEditEnabledChange: (Boolean) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current

    // These controls are self-contained on this second-level page instead of threading another
    // callback through the navigation layer for each one. Most hook settings are read live; the
    // launcher AOT controls below explicitly request a launcher restart.
    var unlockVisual by remember { mutableStateOf(Preferences.unlockMoreVisualPerception()) }
    var unlockGestures by remember { mutableStateOf(Preferences.unlockMoreAonGestures()) }
    var unlockAdaptiveRefresh by remember { mutableStateOf(Preferences.unlockAdaptiveRefreshPro()) }
    var hideRecentsClearButton by remember { mutableStateOf(Preferences.hideRecentsClearButton()) }
    var openedFolderColumns by remember { mutableIntStateOf(Preferences.openedFolderColumns()) }
    var launcherRestartPending by rememberSaveable { mutableStateOf(false) }

    // Notification switches. 恢复更多通知设置 repairs a shell in each of the two processes that own
    // notification settings (the channel page in com.android.settings, the status-bar silent-notification
    // filter in com.android.systemui); the other two are single-process. Each affected process gets
    // its own restart prompt.
    var notifMoreSettings by remember { mutableStateOf(Preferences.notificationMoreSettings()) }
    var notifBadge by remember { mutableStateOf(Preferences.notificationBadge()) }
    var notifBlockFold by remember { mutableStateOf(Preferences.notificationBlockFold()) }
    var notifSettingsRestartPending by rememberSaveable { mutableStateOf(false) }
    var notifSystemUiRestartPending by rememberSaveable { mutableStateOf(false) }

    fun requestLauncherRestart() {
        launcherRestartPending = true
        requestRestartScopes(RestartScopeSelection(miuiHome = true))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.experimental_features_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.experimental_features_back))
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

            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(R.string.experimental_features_notes),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // The material style (材质风格) with its two modes only exists on OS4; OS3 SystemUI has
            // neither the bionics resources nor the material_style key.
            if (PlatformLevel.isOs4) {
                SmallTitle(stringResource(R.string.experimental_features_section))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_glass_material_tuner),
                            summary = stringResource(R.string.settings_glass_material_tuner_summary),
                            onClick = onNavigateToGlassTuner
                        )
                        // Control Center custom corner radius. The radius lives in the OS4 control
                        // center plugin classes, so the entry is OS4-only.
                        ArrowPreference(
                            title = stringResource(R.string.tweaks_cc_corner_enabled_title),
                            summary = stringResource(R.string.tweaks_cc_corner_enabled_summary),
                            onClick = onNavigateToControlCenterCorner
                        )
                        // Control-center editor cards: the fixed main-panel contents (big cards,
                        // media player, brightness/volume sliders, device center) show up in
                        // 编辑与排序 and become drag-reorderable like the quick actions.
                        SwitchPreference(
                            checked = ccEditEnabled,
                            onCheckedChange = onCcEditEnabledChange,
                            title = stringResource(R.string.settings_cc_edit_title),
                            summary = stringResource(R.string.settings_cc_edit_summary)
                        )
                        // Control-center element sizes (big cards, sliders, media player, device
                        // center) plus quick switches rendered as big cards.
                        ArrowPreference(
                            title = stringResource(R.string.cc_resize_title),
                            summary = stringResource(R.string.cc_resize_enabled_summary),
                            onClick = onNavigateToControlCenterResize
                        )
                    }
                }

                SmallTitle(stringResource(R.string.settings_opened_folder_section))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
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
                                requestLauncherRestart()
                            }
                        )
                        if (launcherRestartPending) {
                            ArrowPreference(
                                title = stringResource(R.string.settings_opened_folder_restart_title),
                                summary = stringResource(R.string.settings_opened_folder_restart_summary),
                                onClick = {
                                    Preferences.flush()
                                    RestartUtils.restartScope(
                                        context = context,
                                        coroutineScope = coroutineScope,
                                        selection = RestartScopeSelection(miuiHome = true)
                                    )
                                    handleRestartedScopes(
                                        RestartScopeSelection(miuiHome = true)
                                    )
                                    launcherRestartPending = false
                                }
                            )
                        }
                    }
                }
            }

            // AON visual-perception / air-gesture unlocks: reveal Settings entries that the device
            // hides behind `config_aon_*` resource gates (see VisualPerceptionSettingsHooker).
            // These act on the next Settings UI refresh in the same process; the toggles force the
            // Settings-side capability checks only — runtime sensor gates in system_server are
            // separate (see docs/FEATURE_DETAIL.md).
            SmallTitle(stringResource(R.string.settings_unlock_visual_perception_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
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
                    // when `ro.display.enable_pwm_switch` is unset, and let system_server re-apply
                    // the saved mode at boot. Settings-side reveal needs a fresh Settings process;
                    // the runtime re-apply needs a reboot; extra gears still depend on panel/DF support.
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

            // Recents clean-up button. MiuiHome draws its multitasking UI in Flutter, so this one is
            // not an ART hook: the module's native payload patches the Dart AOT function that
            // inserts the button's overlay. The launcher is a separate process that reads the
            // preference when it loads the module, hence the restart requirement.
            SmallTitle(stringResource(R.string.settings_recents_clear_button_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = hideRecentsClearButton,
                        onCheckedChange = {
                            hideRecentsClearButton = it
                            Preferences.putBoolean(Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON, it)
                            // The launcher-side payload cannot read this process's preferences, so
                            // publish both native settings to the shared config file.
                            NativeRuleConfig.publish(context, it, openedFolderColumns)
                        },
                        title = stringResource(R.string.settings_recents_clear_button_title),
                        summary = stringResource(R.string.settings_recents_clear_button_summary)
                    )
                }
            }

            // Notification behavior: repairs to the notification settings HyperOS ships as shells.
            // The first one spans both processes that own them, so the whole group is listed here
            // rather than behind the platform gate above (none of it needs OS4-only resources).
            SmallTitle(stringResource(R.string.experimental_features_section_notification))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    // 1) 恢复更多通知设置 — a code repair in two processes: the channel page's
                    //    「重要性」 dropdown (com.android.settings) and the status-bar silent-notification
                    //    filter (com.android.systemui). The latter has no switch of its own: it only
                    //    makes the stock 「隐藏状态栏中的静音通知」 switch work, and whether silent
                    //    icons are hidden stays whatever the user sets there.
                    SwitchPreference(
                        checked = notifMoreSettings,
                        onCheckedChange = { value ->
                            notifMoreSettings = value
                            Preferences.putBoolean(
                                Preferences.KEY_NOTIFICATION_MORE_SETTINGS,
                                value
                            )
                            Preferences.flush()
                            notifSettingsRestartPending = true
                            notifSystemUiRestartPending = true
                            requestRestartScopes(
                                RestartScopeSelection(settings = true, systemUi = true)
                            )
                        },
                        title = stringResource(
                            R.string.experimental_notification_more_settings_title
                        ),
                        summary = stringResource(
                            R.string.experimental_notification_more_settings_summary
                        )
                    )
                    // 2) 通知角标 — the same defect and the same fix, for 「显示角标」.
                    SwitchPreference(
                        checked = notifBadge,
                        onCheckedChange = { value ->
                            notifBadge = value
                            Preferences.putBoolean(Preferences.KEY_NOTIFICATION_BADGE, value)
                            Preferences.flush()
                            notifSettingsRestartPending = true
                            requestRestartScopes(RestartScopeSelection(settings = true))
                        },
                        title = stringResource(R.string.experimental_notification_badge_title),
                        summary = stringResource(R.string.experimental_notification_badge_summary)
                    )
                    // 3) 禁止折叠通知 — reuse the ROM's own international-build master switch.
                    SwitchPreference(
                        checked = notifBlockFold,
                        onCheckedChange = { value ->
                            notifBlockFold = value
                            Preferences.putBoolean(Preferences.KEY_NOTIFICATION_BLOCK_FOLD, value)
                            Preferences.flush()
                            notifSystemUiRestartPending = true
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
                        },
                        title = stringResource(
                            R.string.experimental_notification_block_fold_title
                        ),
                        summary = stringResource(
                            R.string.experimental_notification_block_fold_summary
                        )
                    )
                    // 4) 隐藏状态栏中的静音通知 has no switch here on purpose: repairing `isSilent`
                    //    (part of 恢复更多通知设置) only makes the stock switch functional, and that
                    //    switch already has a value the user controls in the stock notification
                    //    settings page (隐藏功能 → 通知设置). The module never forces that value.
                    if (notifSettingsRestartPending) {
                        val restartSelection = RestartScopeSelection(settings = true)
                        ArrowPreference(
                            title = stringResource(
                                R.string.experimental_notification_restart_settings_title
                            ),
                            summary = stringResource(
                                R.string.experimental_notification_restart_settings_summary
                            ),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = restartSelection
                                )
                                handleRestartedScopes(restartSelection)
                                notifSettingsRestartPending = false
                            }
                        )
                    }
                    if (notifSystemUiRestartPending) {
                        val restartSelection = RestartScopeSelection(systemUi = true)
                        ArrowPreference(
                            title = stringResource(R.string.aosp_restart_system_ui),
                            summary = stringResource(R.string.aosp_restart_system_ui_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = restartSelection
                                )
                                handleRestartedScopes(restartSelection)
                                notifSystemUiRestartPending = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
