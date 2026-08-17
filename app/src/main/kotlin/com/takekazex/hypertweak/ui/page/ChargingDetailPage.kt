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
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenChargingDetailHooker
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

/**
 * Options for the lockscreen charging detail (Settings → Experimental → Charging Detail Options).
 * State is kept locally and written straight into [Preferences]; the hooker re-reads these keys on
 * every render, so every option here applies live. The master switch installs the hook, so it
 * needs a SystemUI restart, offered through the in-page Restart SystemUI row.
 */
@Composable
fun ChargingDetailPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var enabled by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL, false))
    }
    // Saveable so the pending prompt survives navigating away (Nav3 disposes the entry).
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }
    var multiline by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_MULTILINE, true))
    }
    var intervalMs by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS,
                LockscreenChargingDetailHooker.DEFAULT_INTERVAL_MS
            )
        )
    }
    var fields by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS,
                LockscreenChargingDetailHooker.DEFAULT_FIELDS
            )
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.charging_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.charging_back)) } }
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

            SmallTitle(stringResource(R.string.charging_section_main))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            systemUiRestartPending = true
                            Preferences.putBoolean(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL, checked)
                        },
                        title = stringResource(R.string.charging_enabled_title),
                        summary = stringResource(R.string.charging_enabled_summary)
                    )
                    if (systemUiRestartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.charging_restart_title),
                            summary = stringResource(R.string.charging_restart_summary),
                            onClick = {
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(systemUi = true)
                                )
                                systemUiRestartPending = false
                            }
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.charging_section_layout))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = multiline,
                        onCheckedChange = { enabled ->
                            multiline = enabled
                            Preferences.putBoolean(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_MULTILINE, enabled)
                        },
                        title = stringResource(R.string.charging_multiline_title),
                        summary = stringResource(R.string.charging_multiline_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.charging_section_refresh))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
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
                                Preferences.putInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS, ms)
                            }
                        }
                    )
                }
            }

            SmallTitle(stringResource(R.string.charging_section_fields))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    chargingFieldSwitch(
                        checked = (fields and LockscreenChargingDetailHooker.FIELD_WATTAGE) != 0,
                        title = stringResource(R.string.charging_field_wattage_title),
                        summary = stringResource(R.string.charging_field_wattage_summary),
                        current = fields,
                        bit = LockscreenChargingDetailHooker.FIELD_WATTAGE
                    ) { value ->
                        fields = value
                        Preferences.putInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, value)
                    }
                    chargingFieldSwitch(
                        checked = (fields and LockscreenChargingDetailHooker.FIELD_VOLTAGE) != 0,
                        title = stringResource(R.string.charging_field_voltage_title),
                        summary = stringResource(R.string.charging_field_voltage_summary),
                        current = fields,
                        bit = LockscreenChargingDetailHooker.FIELD_VOLTAGE
                    ) { value ->
                        fields = value
                        Preferences.putInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, value)
                    }
                    chargingFieldSwitch(
                        checked = (fields and LockscreenChargingDetailHooker.FIELD_CURRENT) != 0,
                        title = stringResource(R.string.charging_field_current_title),
                        summary = stringResource(R.string.charging_field_current_summary),
                        current = fields,
                        bit = LockscreenChargingDetailHooker.FIELD_CURRENT
                    ) { value ->
                        fields = value
                        Preferences.putInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, value)
                    }
                    chargingFieldSwitch(
                        checked = (fields and LockscreenChargingDetailHooker.FIELD_TEMPERATURE) != 0,
                        title = stringResource(R.string.charging_field_temperature_title),
                        summary = stringResource(R.string.charging_field_temperature_summary),
                        current = fields,
                        bit = LockscreenChargingDetailHooker.FIELD_TEMPERATURE
                    ) { value ->
                        fields = value
                        Preferences.putInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, value)
                    }
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

/** Toggle one bit of the charging-detail field bitmask. */
@Composable
private fun chargingFieldSwitch(
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
