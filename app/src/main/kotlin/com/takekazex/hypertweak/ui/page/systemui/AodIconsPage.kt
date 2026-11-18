package com.takekazex.hypertweak.ui.page.systemui

import androidx.compose.foundation.layout.only
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** AOD-only battery, Duo, and percentage controls. */
@Composable
fun AodIconsPage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current

    var batteryVisible by rememberStoredSetting(AodSettingsKeys.KEY_AOD_BATTERY_ICON_VISIBLE, settingsStore::putBoolean) { settingsStore.getBoolean(AodSettingsKeys.KEY_AOD_BATTERY_ICON_VISIBLE, true) }
    var duoEnabled by rememberStoredSetting(AodSettingsKeys.KEY_AOD_DUO_ENABLED, settingsStore::putBoolean) { settingsStore.getBoolean(AodSettingsKeys.KEY_AOD_DUO_ENABLED, false) }
    var percentVisible by rememberStoredSetting(AodSettingsKeys.KEY_AOD_BATTERY_PERCENT_VISIBLE, settingsStore::putBoolean) { settingsStore.getBoolean(AodSettingsKeys.KEY_AOD_BATTERY_PERCENT_VISIBLE, true) }
    var percentPosition by rememberStoredSetting(AodSettingsKeys.KEY_AOD_PERCENT_POSITION, settingsStore::putInt) { settingsStore.getInt(AodSettingsKeys.KEY_AOD_PERCENT_POSITION, AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT) }
    var centerContent by rememberStoredSetting(AodSettingsKeys.KEY_AOD_DUO_CENTER_CONTENT, settingsStore::putInt) { settingsStore.getInt(AodSettingsKeys.KEY_AOD_DUO_CENTER_CONTENT, AodSettingsKeys.AOD_DUO_CENTER_BATTERY) }
    fun setPercentPosition(value: Int) {
        percentPosition = value
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

    AppScrollPage(
        title = stringResource(R.string.settings_aod_icons_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_aod_icons_back),
    ) {

        AppSection(stringResource(R.string.aod_icons_section_display)) {
            SwitchPreference(
                checked = batteryVisible,
                onCheckedChange = { checked ->
                    batteryVisible = checked
                },
                title = stringResource(R.string.aod_icons_battery_title),
                summary = stringResource(R.string.aod_icons_battery_summary)
            )
            SwitchPreference(
                checked = duoEnabled,
                onCheckedChange = { checked ->
                    duoEnabled = checked
                    if (!checked && percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER) {
                        setPercentPosition(AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT)
                    }
                },
                title = stringResource(R.string.aod_icons_duo_title),
                summary = stringResource(R.string.aod_icons_duo_summary)
            )
            MiuixExpandableContent(visible = duoEnabled) {
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
                        if (selected == AodSettingsKeys.AOD_DUO_CENTER_SIGNAL &&
                            percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER
                        ) {
                            setPercentPosition(AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT)
                        }
                    }
                )
            }
        }

        AppSection(stringResource(R.string.aod_icons_section_percent)) {
            SwitchPreference(
                checked = percentVisible,
                onCheckedChange = { checked ->
                    percentVisible = checked
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