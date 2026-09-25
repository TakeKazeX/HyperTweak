package com.takekazex.hypertweak.ui.page

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

/** AOD-only battery, Duo, and percentage controls. */
@Composable
fun AodIconsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current
    val systemUiScope = remember { RestartScopeSelection(systemUi = true) }

    var batteryVisible by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOD_BATTERY_ICON_VISIBLE, true))
    }
    var duoEnabled by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOD_DUO_ENABLED, false))
    }
    var percentVisible by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOD_BATTERY_PERCENT_VISIBLE, true))
    }
    var percentPosition by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_AOD_PERCENT_POSITION,
                Preferences.AOD_PERCENT_POSITION_RIGHT
            )
        )
    }
    var centerContent by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_AOD_DUO_CENTER_CONTENT,
                Preferences.AOD_DUO_CENTER_BATTERY
            )
        )
    }
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }

    fun requestSystemUiRestart() {
        systemUiRestartPending = true
        requestRestartScopes(systemUiScope)
    }

    fun setPercentPosition(value: Int) {
        percentPosition = value
        Preferences.putInt(Preferences.KEY_AOD_PERCENT_POSITION, value)
        requestSystemUiRestart()
    }

    val canCenterPercent = duoEnabled && centerContent == Preferences.AOD_DUO_CENTER_BATTERY
    val safePercentPosition = if (canCenterPercent) {
        percentPosition.coerceIn(
            Preferences.AOD_PERCENT_POSITION_LEFT,
            Preferences.AOD_PERCENT_POSITION_DUO_CENTER
        )
    } else {
        percentPosition.coerceIn(
            Preferences.AOD_PERCENT_POSITION_LEFT,
            Preferences.AOD_PERCENT_POSITION_RIGHT
        )
    }
    val percentPositionItems = if (canCenterPercent) {
        listOf(
            stringResource(R.string.aod_icons_percent_left),
            stringResource(R.string.aod_icons_percent_right),
            stringResource(R.string.aod_icons_percent_duo_center)
        )
    } else {
        listOf(
            stringResource(R.string.aod_icons_percent_left),
            stringResource(R.string.aod_icons_percent_right)
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.settings_aod_icons_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.settings_aod_icons_back))
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

            SmallTitle(stringResource(R.string.aod_icons_section_display))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = batteryVisible,
                        onCheckedChange = { checked ->
                            batteryVisible = checked
                            Preferences.putBoolean(Preferences.KEY_AOD_BATTERY_ICON_VISIBLE, checked)
                            requestSystemUiRestart()
                        },
                        title = stringResource(R.string.aod_icons_battery_title),
                        summary = stringResource(R.string.aod_icons_battery_summary)
                    )
                    SwitchPreference(
                        checked = duoEnabled,
                        onCheckedChange = { checked ->
                            duoEnabled = checked
                            Preferences.putBoolean(Preferences.KEY_AOD_DUO_ENABLED, checked)
                            if (!checked && percentPosition == Preferences.AOD_PERCENT_POSITION_DUO_CENTER) {
                                setPercentPosition(Preferences.AOD_PERCENT_POSITION_RIGHT)
                            } else {
                                requestSystemUiRestart()
                            }
                        },
                        title = stringResource(R.string.aod_icons_duo_title),
                        summary = stringResource(R.string.aod_icons_duo_summary)
                    )
                    if (duoEnabled) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.aod_icons_duo_center_title),
                            summary = stringResource(R.string.aod_icons_duo_center_summary),
                            items = listOf(
                                stringResource(R.string.aod_icons_duo_center_battery),
                                stringResource(R.string.aod_icons_duo_center_signal)
                            ),
                            selectedIndex = centerContent.coerceIn(
                                Preferences.AOD_DUO_CENTER_BATTERY,
                                Preferences.AOD_DUO_CENTER_SIGNAL
                            ),
                            onSelectedIndexChange = { selected ->
                                centerContent = selected
                                Preferences.putInt(Preferences.KEY_AOD_DUO_CENTER_CONTENT, selected)
                                if (selected == Preferences.AOD_DUO_CENTER_SIGNAL &&
                                    percentPosition == Preferences.AOD_PERCENT_POSITION_DUO_CENTER
                                ) {
                                    setPercentPosition(Preferences.AOD_PERCENT_POSITION_RIGHT)
                                } else {
                                    requestSystemUiRestart()
                                }
                            }
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.aod_icons_section_percent))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = percentVisible,
                        onCheckedChange = { checked ->
                            percentVisible = checked
                            Preferences.putBoolean(Preferences.KEY_AOD_BATTERY_PERCENT_VISIBLE, checked)
                            requestSystemUiRestart()
                        },
                        title = stringResource(R.string.aod_icons_percent_visible_title),
                        summary = stringResource(R.string.aod_icons_percent_visible_summary)
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.aod_icons_percent_position_title),
                        summary = stringResource(R.string.aod_icons_percent_position_summary),
                        items = percentPositionItems,
                        selectedIndex = safePercentPosition,
                        onSelectedIndexChange = { selected -> setPercentPosition(selected) }
                    )
                }
            }

            if (systemUiRestartPending) {
                SmallTitle(stringResource(R.string.aod_icons_section_apply))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.aod_icons_restart_title),
                        summary = stringResource(R.string.aod_icons_restart_summary),
                        onClick = {
                            Preferences.flush()
                            RestartUtils.restartScope(
                                context = context,
                                coroutineScope = coroutineScope,
                                selection = systemUiScope
                            )
                            handleRestartedScopes(systemUiScope)
                            systemUiRestartPending = false
                        }
                    )
                }
            }
        }
    }
}
