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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.takekazex.hypertweak.hook.rules.systemui.NotificationHeaderModel
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Settings for OS4's new-control-center notification header. */
@Composable
fun NotificationHeaderPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current

    var restartPending by rememberSaveable { mutableStateOf(false) }
    var hideCarrier by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_CARRIER, false))
    }
    var hideTime by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_TIME, false))
    }
    var hideDate by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_DATE, false))
    }
    var dateAboveTime by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_DATE_ABOVE_TIME, false)
        )
    }
    var dateAlignment by remember {
        mutableIntStateOf(
            NotificationHeaderModel.normalizeAlignment(
                Preferences.getInt(
                    Preferences.KEY_NOTIFICATION_HEADER_DATE_ALIGNMENT,
                    NotificationHeaderModel.ALIGN_START
                )
            )
        )
    }
    var timeAlignment by remember {
        mutableIntStateOf(
            NotificationHeaderModel.normalizeAlignment(
                Preferences.getInt(
                    Preferences.KEY_NOTIFICATION_HEADER_TIME_ALIGNMENT,
                    NotificationHeaderModel.ALIGN_START
                )
            )
        )
    }
    var timeScale by remember {
        mutableFloatStateOf(
            NotificationHeaderModel.normalizeTimeScale(
                Preferences.getNotificationHeaderTimeScale()
            )
        )
    }
    var weatherEnabled by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED, false)
        )
    }
    var weatherRegion by remember {
        mutableStateOf(
            Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_WEATHER_REGION, false)
        )
    }
    var weatherType by remember {
        mutableIntStateOf(
            NotificationHeaderModel.normalizeWeatherType(
                Preferences.getInt(
                    Preferences.KEY_NOTIFICATION_HEADER_WEATHER_TYPE,
                    NotificationHeaderModel.WEATHER_CONDITION
                )
            )
        )
    }

    fun markRestartPending() {
        restartPending = true
        requestRestartScopes(RestartScopeSelection(systemUi = true))
    }

    val alignmentItems = listOf(
        stringResource(R.string.notification_header_alignment_start),
        stringResource(R.string.notification_header_alignment_center),
        stringResource(R.string.notification_header_alignment_end)
    )

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.notification_header_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.notification_header_back))
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

            SmallTitle(stringResource(R.string.notification_header_section_scope))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.notification_header_scope_title))
                        Text(stringResource(R.string.notification_header_scope_summary))
                    }
                    if (restartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.notification_header_restart_title),
                            summary = stringResource(R.string.notification_header_restart_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(systemUi = true)
                                )
                                handleRestartedScopes(RestartScopeSelection(systemUi = true))
                                restartPending = false
                            }
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.notification_header_section_visibility))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = hideCarrier,
                        onCheckedChange = {
                            hideCarrier = it
                            Preferences.putBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_CARRIER, it)
                            markRestartPending()
                        },
                        title = stringResource(R.string.notification_header_hide_carrier),
                        summary = stringResource(R.string.notification_header_hide_carrier_summary)
                    )
                    SwitchPreference(
                        checked = hideTime,
                        onCheckedChange = {
                            hideTime = it
                            Preferences.putBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_TIME, it)
                            markRestartPending()
                        },
                        title = stringResource(R.string.notification_header_hide_time),
                        summary = stringResource(R.string.notification_header_hide_time_summary)
                    )
                    SwitchPreference(
                        checked = hideDate,
                        onCheckedChange = {
                            hideDate = it
                            Preferences.putBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_DATE, it)
                            markRestartPending()
                        },
                        title = stringResource(R.string.notification_header_hide_date),
                        summary = stringResource(R.string.notification_header_hide_date_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.notification_header_section_layout))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = dateAboveTime,
                        onCheckedChange = {
                            dateAboveTime = it
                            Preferences.putBoolean(
                                Preferences.KEY_NOTIFICATION_HEADER_DATE_ABOVE_TIME,
                                it
                            )
                            markRestartPending()
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
                            Preferences.putInt(
                                Preferences.KEY_NOTIFICATION_HEADER_DATE_ALIGNMENT,
                                dateAlignment
                            )
                            markRestartPending()
                        }
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.notification_header_time_alignment),
                        summary = stringResource(R.string.notification_header_time_alignment_summary),
                        items = alignmentItems,
                        selectedIndex = timeAlignment,
                        onSelectedIndexChange = { index ->
                            timeAlignment = NotificationHeaderModel.normalizeAlignment(index)
                            Preferences.putInt(
                                Preferences.KEY_NOTIFICATION_HEADER_TIME_ALIGNMENT,
                                timeAlignment
                            )
                            markRestartPending()
                        }
                    )
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            stringResource(
                                R.string.notification_header_time_scale,
                                (timeScale * 100f).roundToInt()
                            )
                        )
                        Slider(
                            value = timeScale,
                            onValueChange = { value ->
                                timeScale = (value * 20f).roundToInt() / 20f
                                Preferences.putFloat(
                                    Preferences.KEY_NOTIFICATION_HEADER_TIME_SCALE,
                                    timeScale
                                )
                                markRestartPending()
                            },
                            onValueChangeFinished = { },
                            valueRange = NotificationHeaderModel.MIN_TIME_SCALE..
                                NotificationHeaderModel.MAX_TIME_SCALE,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.notification_header_section_weather))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = weatherEnabled,
                        onCheckedChange = {
                            weatherEnabled = it
                            Preferences.putBoolean(
                                Preferences.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED,
                                it
                            )
                            markRestartPending()
                        },
                        title = stringResource(R.string.notification_header_weather_enabled),
                        summary = stringResource(R.string.notification_header_weather_enabled_summary)
                    )
                    if (weatherEnabled) {
                        SwitchPreference(
                            checked = weatherRegion,
                            onCheckedChange = {
                                weatherRegion = it
                                Preferences.putBoolean(
                                    Preferences.KEY_NOTIFICATION_HEADER_WEATHER_REGION,
                                    it
                                )
                                markRestartPending()
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
                                Preferences.putInt(
                                    Preferences.KEY_NOTIFICATION_HEADER_WEATHER_TYPE,
                                    weatherType
                                )
                                markRestartPending()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
