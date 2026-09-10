package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Column
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
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Security Center-scoped feature switches, grouped independently from AOSP restoration. */
@Composable
fun SecurityCenterPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current

    var lbeClipboardToast by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_LBE_CLIPBOARD_TOAST, false))
    }
    var bubbleNotificationUnlock by remember {
        mutableStateOf(
            Preferences.getBoolean(
                Preferences.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK,
                false
            )
        )
    }
    var lowBatteryWarningMode by remember {
        mutableStateOf(
            when {
                Preferences.contains(Preferences.KEY_SECURITY_CENTER_LOW_BATTERY_MODE) ->
                    Preferences.getInt(
                        Preferences.KEY_SECURITY_CENTER_LOW_BATTERY_MODE,
                        Preferences.SECURITY_CENTER_LOW_BATTERY_FOLLOW
                    ).coerceIn(
                        Preferences.SECURITY_CENTER_LOW_BATTERY_FOLLOW,
                        Preferences.SECURITY_CENTER_LOW_BATTERY_SILENT
                    )
                Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_HIDE_LOW_BATTERY_WARNING, false) ->
                    Preferences.SECURITY_CENTER_LOW_BATTERY_SILENT
                else -> Preferences.SECURITY_CENTER_LOW_BATTERY_FOLLOW
            }
        )
    }
    var showDetailedPowerData by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA, false)
        )
    }
    var restorePowerRanking by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING, false)
        )
    }
    var showBerserkMode by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE, false)
        )
    }
    var moreBatteryInfo by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_MORE_BATTERY_INFO, false)
        )
    }
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }
    var securityCenterRestartPending by rememberSaveable { mutableStateOf(false) }

    fun requestRestart(selection: RestartScopeSelection) {
        if (selection.systemUi) systemUiRestartPending = true
        if (selection.securityCenter) securityCenterRestartPending = true
        requestRestartScopes(selection)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.security_center_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, stringResource(R.string.aosp_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            androidx.compose.foundation.layout.Spacer(
                Modifier.height(padding.calculateTopPadding() + 8.dp)
            )

            SmallTitle(stringResource(R.string.security_center_clipboard_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = lbeClipboardToast,
                    onCheckedChange = { enabled ->
                        lbeClipboardToast = enabled
                        requestRestart(
                            RestartScopeSelection(
                                additionalPackages = setOf(RestartScopeSelection.PACKAGE_LBE_SECURITY)
                            )
                        )
                        Preferences.putBoolean(Preferences.KEY_LBE_CLIPBOARD_TOAST, enabled)
                    },
                    title = stringResource(R.string.tweaks_lbe_clipboard_toast_title),
                    summary = stringResource(R.string.tweaks_lbe_clipboard_toast_summary)
                )
            }

            SmallTitle(stringResource(R.string.security_center_notifications_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = bubbleNotificationUnlock,
                    onCheckedChange = { enabled ->
                        bubbleNotificationUnlock = enabled
                        requestRestart(
                            RestartScopeSelection(systemUi = true, securityCenter = true)
                        )
                        Preferences.putBoolean(
                            Preferences.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK,
                            enabled
                        )
                    },
                    title = stringResource(R.string.security_bubble_notification_unlock),
                    summary = stringResource(R.string.security_bubble_notification_unlock_summary)
                )
            }

            SmallTitle(stringResource(R.string.security_center_battery_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        items = listOf(
                            stringResource(R.string.security_center_low_battery_follow),
                            stringResource(R.string.security_center_low_battery_hide_dialog),
                            stringResource(R.string.security_center_low_battery_silent)
                        ),
                        selectedIndex = lowBatteryWarningMode,
                        onSelectedIndexChange = { mode ->
                            lowBatteryWarningMode = mode
                            requestRestart(RestartScopeSelection(securityCenter = true))
                            Preferences.putInt(
                                Preferences.KEY_SECURITY_CENTER_LOW_BATTERY_MODE,
                                mode
                            )
                        },
                        title = stringResource(R.string.security_center_hide_low_battery_warning),
                        summary = stringResource(R.string.security_center_hide_low_battery_warning_summary)
                    )
                    SwitchPreference(
                        checked = showDetailedPowerData,
                        onCheckedChange = { enabled ->
                            showDetailedPowerData = enabled
                            requestRestart(RestartScopeSelection(securityCenter = true))
                            Preferences.putBoolean(
                                Preferences.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA,
                                enabled
                            )
                        },
                        title = stringResource(R.string.security_center_show_detailed_power_data),
                        summary = stringResource(R.string.security_center_show_detailed_power_data_summary)
                    )
                    SwitchPreference(
                        checked = restorePowerRanking,
                        onCheckedChange = { enabled ->
                            restorePowerRanking = enabled
                            requestRestart(RestartScopeSelection(securityCenter = true))
                            Preferences.putBoolean(
                                Preferences.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING,
                                enabled
                            )
                        },
                        title = stringResource(R.string.security_center_restore_power_ranking),
                        summary = stringResource(R.string.security_center_restore_power_ranking_summary)
                    )
                    SwitchPreference(
                        checked = showBerserkMode,
                        onCheckedChange = { enabled ->
                            showBerserkMode = enabled
                            requestRestart(RestartScopeSelection(securityCenter = true))
                            Preferences.putBoolean(
                                Preferences.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE,
                                enabled
                            )
                        },
                        title = stringResource(R.string.security_center_show_berserk_mode),
                        summary = stringResource(R.string.security_center_show_berserk_mode_summary)
                    )
                    SwitchPreference(
                        checked = moreBatteryInfo,
                        onCheckedChange = { enabled ->
                            moreBatteryInfo = enabled
                            requestRestart(RestartScopeSelection(securityCenter = true))
                            Preferences.putBoolean(
                                Preferences.KEY_SECURITY_CENTER_MORE_BATTERY_INFO,
                                enabled
                            )
                        },
                        title = stringResource(R.string.security_center_more_battery_info),
                        summary = stringResource(R.string.security_center_more_battery_info_summary)
                    )
                }
            }

            if (systemUiRestartPending || securityCenterRestartPending) {
                val restartSelection = RestartScopeSelection(
                    systemUi = systemUiRestartPending,
                    securityCenter = securityCenterRestartPending
                )
                SmallTitle(stringResource(R.string.security_center_apply_section))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(
                            when {
                                systemUiRestartPending && securityCenterRestartPending ->
                                    R.string.aosp_restart_security_center_and_system_ui
                                securityCenterRestartPending -> R.string.aosp_restart_security_center
                                else -> R.string.aosp_restart_system_ui
                            }
                        ),
                        summary = stringResource(R.string.aosp_restart_security_center_summary),
                        onClick = {
                            Preferences.flush()
                            RestartUtils.restartScope(
                                context = context,
                                coroutineScope = coroutineScope,
                                selection = restartSelection
                            )
                            handleRestartedScopes(restartSelection)
                            systemUiRestartPending = false
                            securityCenterRestartPending = false
                        }
                    )
                }
            }

            androidx.compose.foundation.layout.Spacer(
                Modifier.height(padding.calculateBottomPadding() + 16.dp)
            )
        }
    }
}
