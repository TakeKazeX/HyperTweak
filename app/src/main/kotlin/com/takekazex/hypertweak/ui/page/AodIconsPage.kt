package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.settings.contract.AodSettingsKeys

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** AOD-only battery, Duo, and percentage controls. */
@Composable
fun AodIconsPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()

    var batteryVisible by remember {
        mutableStateOf(Preferences.getBoolean(AodSettingsKeys.KEY_AOD_BATTERY_ICON_VISIBLE, true))
    }
    var duoEnabled by remember {
        mutableStateOf(Preferences.getBoolean(AodSettingsKeys.KEY_AOD_DUO_ENABLED, false))
    }
    var percentVisible by remember {
        mutableStateOf(Preferences.getBoolean(AodSettingsKeys.KEY_AOD_BATTERY_PERCENT_VISIBLE, true))
    }
    var percentPosition by remember {
        mutableIntStateOf(
            Preferences.getInt(
                AodSettingsKeys.KEY_AOD_PERCENT_POSITION,
                AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT
            )
        )
    }
    var centerContent by remember {
        mutableIntStateOf(
            Preferences.getInt(
                AodSettingsKeys.KEY_AOD_DUO_CENTER_CONTENT,
                AodSettingsKeys.AOD_DUO_CENTER_BATTERY
            )
        )
    }
    fun setPercentPosition(value: Int) {
        percentPosition = value
        Preferences.putInt(AodSettingsKeys.KEY_AOD_PERCENT_POSITION, value)
    }

    val canCenterPercent = duoEnabled && centerContent == AodSettingsKeys.AOD_DUO_CENTER_BATTERY
    val safePercentPosition = if (canCenterPercent) {
        percentPosition.coerceIn(
            AodSettingsKeys.AOD_PERCENT_POSITION_LEFT,
            AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER
        )
    } else {
        percentPosition.coerceIn(
            AodSettingsKeys.AOD_PERCENT_POSITION_LEFT,
            AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT
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
                            Preferences.putBoolean(AodSettingsKeys.KEY_AOD_BATTERY_ICON_VISIBLE, checked)
                        },
                        title = stringResource(R.string.aod_icons_battery_title),
                        summary = stringResource(R.string.aod_icons_battery_summary)
                    )
                    SwitchPreference(
                        checked = duoEnabled,
                        onCheckedChange = { checked ->
                            duoEnabled = checked
                            Preferences.putBoolean(AodSettingsKeys.KEY_AOD_DUO_ENABLED, checked)
                            if (!checked && percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER) {
                                setPercentPosition(AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT)
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
                                AodSettingsKeys.AOD_DUO_CENTER_BATTERY,
                                AodSettingsKeys.AOD_DUO_CENTER_SIGNAL
                            ),
                            onSelectedIndexChange = { selected ->
                                centerContent = selected
                                Preferences.putInt(AodSettingsKeys.KEY_AOD_DUO_CENTER_CONTENT, selected)
                                if (selected == AodSettingsKeys.AOD_DUO_CENTER_SIGNAL &&
                                    percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER
                                ) {
                                    setPercentPosition(AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT)
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
                            Preferences.putBoolean(AodSettingsKeys.KEY_AOD_BATTERY_PERCENT_VISIBLE, checked)
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

        }
    }
}