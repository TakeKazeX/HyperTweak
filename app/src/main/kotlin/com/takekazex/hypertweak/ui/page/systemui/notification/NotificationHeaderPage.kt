package com.takekazex.hypertweak.ui.page.systemui.notification

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.NotificationHeaderModel
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Settings for OS4's new-control-center notification header. */
@Composable
fun NotificationHeaderPage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current

    var hideCarrier by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_CARRIER, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_CARRIER, false) }
    var hideTime by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_TIME, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_TIME, false) }
    var hideDate by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_DATE, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_DATE, false) }
    var dateAboveTime by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_DATE_ABOVE_TIME, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_DATE_ABOVE_TIME, false) }
    var dateAlignment by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_DATE_ALIGNMENT, settingsStore::putInt) { NotificationHeaderModel.normalizeAlignment(settingsStore.getInt(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_DATE_ALIGNMENT, NotificationHeaderModel.ALIGN_START)) }
    var timeAlignment by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_TIME_ALIGNMENT, settingsStore::putInt) { NotificationHeaderModel.normalizeAlignment(settingsStore.getInt(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_TIME_ALIGNMENT, NotificationHeaderModel.ALIGN_START)) }
    var timeScale by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_TIME_SCALE, settingsStore::putFloat) { NotificationHeaderModel.normalizeTimeScale(settingsStore.systemUi.getNotificationHeaderTimeScale()) }
    val timeScaleSlider = rememberSliderEdit(timeScale)
    var weatherEnabled by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED, false) }
    var weatherRegion by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_REGION, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_REGION, false) }
    var weatherType by rememberStoredSetting(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_TYPE, settingsStore::putInt) { NotificationHeaderModel.normalizeWeatherType(settingsStore.getInt(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_TYPE, NotificationHeaderModel.WEATHER_CONDITION)) }

    val alignmentItems = listOf(
        stringResource(R.string.notification_header_alignment_start),
        stringResource(R.string.notification_header_alignment_center),
        stringResource(R.string.notification_header_alignment_end)
    )

    AppScrollPage(
        title = stringResource(R.string.notification_header_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.notification_header_back),
    ) {

        AppSection(stringResource(R.string.notification_header_section_scope)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.notification_header_scope_title))
                Text(stringResource(R.string.notification_header_scope_summary))
            }
        }

        AppSection(stringResource(R.string.notification_header_section_visibility)) {
            SwitchPreference(
                checked = hideCarrier,
                onCheckedChange = {
                    hideCarrier = it

                },
                title = stringResource(R.string.notification_header_hide_carrier),
                summary = stringResource(R.string.notification_header_hide_carrier_summary)
            )
            SwitchPreference(
                checked = hideTime,
                onCheckedChange = {
                    hideTime = it

                },
                title = stringResource(R.string.notification_header_hide_time),
                summary = stringResource(R.string.notification_header_hide_time_summary)
            )
            SwitchPreference(
                checked = hideDate,
                onCheckedChange = {
                    hideDate = it

                },
                title = stringResource(R.string.notification_header_hide_date),
                summary = stringResource(R.string.notification_header_hide_date_summary)
            )
        }

        AppSection(stringResource(R.string.notification_header_section_layout)) {
            SwitchPreference(
                checked = dateAboveTime,
                onCheckedChange = {
                    dateAboveTime = it

                },
                title = stringResource(R.string.notification_header_date_above_time),
                summary = stringResource(R.string.notification_header_date_above_time_summary)
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.notification_header_date_alignment),
                summary = stringResource(R.string.notification_header_date_alignment_summary),
                items = alignmentItems,
                selectedIndex = dateAlignment,
                onSelectedIndexChange = { index ->
                    dateAlignment = NotificationHeaderModel.normalizeAlignment(index)

                }
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.notification_header_time_alignment),
                summary = stringResource(R.string.notification_header_time_alignment_summary),
                items = alignmentItems,
                selectedIndex = timeAlignment,
                onSelectedIndexChange = { index ->
                    timeAlignment = NotificationHeaderModel.normalizeAlignment(index)

                }
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    stringResource(
                        R.string.notification_header_time_scale,
                        (timeScaleSlider.value * 100f).roundToInt()
                    )
                )
                Slider(
                    value = timeScaleSlider.value,
                    onValueChange = { timeScaleSlider.update((it * 20f).roundToInt() / 20f) },
                    onValueChangeFinished = {
                        timeScaleSlider.finish()?.let { value ->
                            timeScale = value

                        }
                    },
                    valueRange = NotificationHeaderModel.MIN_TIME_SCALE..
                        NotificationHeaderModel.MAX_TIME_SCALE,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )
            }
        }

        AppSection(stringResource(R.string.notification_header_section_weather)) {
            SwitchPreference(
                checked = weatherEnabled,
                onCheckedChange = {
                    weatherEnabled = it

                },
                title = stringResource(R.string.notification_header_weather_enabled),
                summary = stringResource(R.string.notification_header_weather_enabled_summary)
            )
            MiuixExpandableContent(visible = weatherEnabled) {
                SwitchPreference(
                    checked = weatherRegion,
                    onCheckedChange = {
                        weatherRegion = it

                    },
                    title = stringResource(R.string.notification_header_weather_region),
                    summary = stringResource(R.string.notification_header_weather_region_summary)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.notification_header_weather_type),
                    summary = stringResource(R.string.notification_header_weather_type_summary),
                    items = listOf(
                        stringResource(R.string.notification_header_weather_condition),
                        stringResource(R.string.notification_header_weather_temperature),
                        stringResource(R.string.notification_header_weather_rain)
                    ),
                    selectedIndex = weatherType,
                    onSelectedIndexChange = { index ->
                        weatherType = NotificationHeaderModel.normalizeWeatherType(index)

                    }
                )
            }
        }
    }
}
