package com.takekazex.hypertweak.ui.page.systemui.lockscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.LockscreenChargingOptions
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Options for the lockscreen charging detail (Features → System UI → Lockscreen → Charging Detail Options).
 * State is kept locally and written straight into [AppSettingsStore]; the hooker re-reads these keys on
 * every render, so every option here applies live, including the master switch (the runtime settings
 * dispatcher installs or retires the hook without a SystemUI restart).
 */
@Composable
fun ChargingDetailPage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current

    var enabled by rememberStoredSetting(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL, false) }
    var twoRows by rememberStoredSetting(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_TWO_ROWS, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_TWO_ROWS, true) }
    var intervalMs by rememberStoredSetting(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS, settingsStore::putInt) { settingsStore.getInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS, LockscreenChargingOptions.DEFAULT_INTERVAL_MS) }
    var fields by rememberStoredSetting(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, settingsStore::putInt) { settingsStore.getInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, LockscreenChargingOptions.DEFAULT_FIELDS) }

    AppScrollPage(
        title = stringResource(R.string.charging_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.charging_back),
    ) {

        AppSection(title = stringResource(R.string.charging_section_main)) {
            SwitchPreference(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                },
                title = stringResource(R.string.charging_enabled_title),
                summary = stringResource(R.string.charging_enabled_summary)
            )
        }

        AppSection(title = stringResource(R.string.charging_section_layout)) {
            SwitchPreference(
                checked = twoRows,
                onCheckedChange = { checked ->
                    twoRows = checked
                },
                title = stringResource(R.string.charging_two_rows_title),
                summary = stringResource(R.string.charging_two_rows_summary)
            )
        }

        AppSection(title = stringResource(R.string.charging_section_refresh)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.charging_interval_title),
                summary = stringResource(R.string.charging_interval_summary),
                items = listOf(
                    stringResource(R.string.charging_interval_1s),
                    stringResource(R.string.charging_interval_2s),
                    stringResource(R.string.charging_interval_3s),
                    stringResource(R.string.charging_interval_5s)
                ),
                selectedIndex = when (intervalMs) {
                    1000 -> 0
                    3000 -> 2
                    5000 -> 3
                    else -> 1
                },
                onSelectedIndexChange = { index ->
                    listOf(1000, 2000, 3000, 5000).getOrNull(index)?.let { ms ->
                        intervalMs = ms
                    }
                }
            )
        }

        AppSection(title = stringResource(R.string.charging_section_fields)) {
            ChargingFieldSwitch(
                checked = (fields and LockscreenChargingOptions.FIELD_WATTAGE) != 0,
                title = stringResource(R.string.charging_field_wattage_title),
                summary = stringResource(R.string.charging_field_wattage_summary),
                current = fields,
                bit = LockscreenChargingOptions.FIELD_WATTAGE
            ) { value ->
                fields = value
            }
            ChargingFieldSwitch(
                checked = (fields and LockscreenChargingOptions.FIELD_VOLTAGE) != 0,
                title = stringResource(R.string.charging_field_voltage_title),
                summary = stringResource(R.string.charging_field_voltage_summary),
                current = fields,
                bit = LockscreenChargingOptions.FIELD_VOLTAGE
            ) { value ->
                fields = value
            }
            ChargingFieldSwitch(
                checked = (fields and LockscreenChargingOptions.FIELD_CURRENT) != 0,
                title = stringResource(R.string.charging_field_current_title),
                summary = stringResource(R.string.charging_field_current_summary),
                current = fields,
                bit = LockscreenChargingOptions.FIELD_CURRENT
            ) { value ->
                fields = value
            }
            ChargingFieldSwitch(
                checked = (fields and LockscreenChargingOptions.FIELD_TEMPERATURE) != 0,
                title = stringResource(R.string.charging_field_temperature_title),
                summary = stringResource(R.string.charging_field_temperature_summary),
                current = fields,
                bit = LockscreenChargingOptions.FIELD_TEMPERATURE
            ) { value ->
                fields = value
            }
        }
    }
}

/** Toggle one bit of the charging-detail field bitmask. */
@Composable
private fun ChargingFieldSwitch(
    checked: Boolean,
    title: String,
    summary: String,
    current: Int,
    bit: Int,
    onChange: (Int) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = { on -> onChange(if (on) current or bit else current and bit.inv()) },
        title = title,
        summary = summary
    )
}
