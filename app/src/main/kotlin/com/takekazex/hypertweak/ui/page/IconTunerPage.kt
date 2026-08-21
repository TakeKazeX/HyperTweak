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
import androidx.compose.runtime.mutableStateMapOf
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
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Status-bar icon customization, ported from Hyper Helper's icon tuner
 * (see the reverse-engineering workspace, cache/xiaomihelper-2bfd4873a4138764, and
 * OS4_ADAPTATION_PLAN.md). State is kept locally rather than hoisted into `MainActivity`, so these
 * switches do not feed the pending-restart-scope tracking; every change requires a SystemUI
 * restart and the page offers one.
 */

@Composable
private fun TunerSwitch(
    checked: Boolean,
    title: String,
    summary: String,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = title,
        summary = summary
    )
}

@Composable
private fun TunerSwitch(
    checked: Boolean,
    title: String,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = title
    )
}

@Composable
fun IconTunerPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }

    // Local remember-backed state so toggles update instantly; the remote preference write is
    // async, so reading Preferences directly in composition made switches bounce back.
    val prefs = androidx.compose.runtime.remember { mutableStateMapOf<String, Any>() }

    @Suppress("UNCHECKED_CAST")
    fun <T> pref(key: String, default: T): T {
        val existing = prefs[key]
        if (existing != null) return existing as T
        // Load the stored value once; defaults are only for keys never written.
        val stored: T = when (default) {
            is Boolean -> Preferences.getBoolean(key, default as Boolean) as T
            is Int -> Preferences.getInt(key, default as Int) as T
            is Float -> Preferences.getFloat(key, default as Float) as T
            is String -> Preferences.getString(key, default as String) as T
            else -> default
        }
        prefs[key] = stored as Any
        return stored
    }

    fun changed(key: String, value: Any) {
        prefs[key] = value
        when (value) {
            is Boolean -> Preferences.putBoolean(key, value)
            is Int -> Preferences.putInt(key, value)
            is Float -> Preferences.putFloat(key, value)
            is String -> Preferences.putString(key, value)
        }
        systemUiRestartPending = true
    }

    // Modes follow Hyper Helper: 0 = follow system (lists untouched), 1 = visible everywhere,
    // 2 = status bar only, 3 = control center only, 4 = hidden everywhere.
    val slotModes = listOf(
        stringResource(R.string.icon_slot_mode_follow),
        stringResource(R.string.icon_slot_mode_visible),
        stringResource(R.string.icon_slot_mode_status_bar),
        stringResource(R.string.icon_slot_mode_control_center),
        stringResource(R.string.icon_slot_mode_hidden)
    )
    val commonSlots = listOf(
        "mobile", "no_sim", "airplane", "wifi", "demo_wifi", "hotspot", "vpn",
        "network_speed", "bluetooth", "bluetooth_handsfree_battery", "handle_battery",
        "nfc", "gps", "location", "wireless_headset", "phone", "pad", "pc",
        "sound_box_group", "stereo", "sound_box_screen", "sound_box", "tv", "glasses",
        "car", "camera", "dist_compute", "headset", "alarm_clock", "zen", "volume",
        "second_space", "compound_icon"
    )

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.icon_page_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.icon_back)) } }
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

            StackedSignalSection(
                enabled = pref(Preferences.KEY_ICON_STACKED_ENABLED, false),
                scale = pref(Preferences.KEY_ICON_STACKED_SCALE, 1f),
                onChange = { key, value -> changed(key, value) }
            )

            LeftContainerSection(
                enabled = pref(Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED, false),
                zen = pref(Preferences.KEY_ICON_LEFT_ZEN, false),
                volume = pref(Preferences.KEY_ICON_LEFT_VOLUME, false),
                hotspot = pref(Preferences.KEY_ICON_LEFT_HOTSPOT, false),
                alarmClock = pref(Preferences.KEY_ICON_LEFT_ALARM_CLOCK, false),
                location = pref(Preferences.KEY_ICON_LEFT_LOCATION, false),
                bluetooth = pref(Preferences.KEY_ICON_LEFT_BLUETOOTH, false),
                nfc = pref(Preferences.KEY_ICON_LEFT_NFC, false),
                vpn = pref(Preferences.KEY_ICON_LEFT_VPN, false),
                airplane = pref(Preferences.KEY_ICON_LEFT_AIRPLANE, false),
                headset = pref(Preferences.KEY_ICON_LEFT_HEADSET, false),
                onChange = { key, value -> changed(key, value) }
            )

            CellularSection(
                activity = pref(Preferences.KEY_ICON_HIDE_CELLULAR_ACTIVITY, false),
                type = pref(Preferences.KEY_ICON_HIDE_CELLULAR_TYPE, false),
                roam = pref(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM, false),
                smallRoam = pref(Preferences.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM, false),
                roamGlobal = pref(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL, false),
                voWifi = pref(Preferences.KEY_ICON_HIDE_CELLULAR_VOWIFI, false),
                volte = pref(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE, false),
                volteNoService = pref(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, false),
                speechHd = pref(Preferences.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, false),
                hideNonDefaultSim = pref(Preferences.KEY_ICON_HIDE_NON_DEFAULT_SIM, false),
                onChange = { key, value ->
                    changed(key, value)
                }
            )

            CellularTypeSection(
                forceSingle = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE, false),
                useCustom = pref(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM, false),
                customValue = pref(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, ""),
                onChange = { key, value -> changed(key, value) }
            )

            WifiSection(
                activity = pref(Preferences.KEY_ICON_HIDE_WIFI_ACTIVITY, false),
                type = pref(Preferences.KEY_ICON_HIDE_WIFI_TYPE, false),
                hideConnected = pref(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false),
                onChange = { key, value -> changed(key, value) }
            )

            CompoundSection(
                slotMode = pref(Preferences.slotKey("compound_icon"), 0),
                alarm = pref(Preferences.KEY_ICON_COMPOUND_ALARM, false),
                zen = pref(Preferences.KEY_ICON_COMPOUND_ZEN, false),
                location = pref(Preferences.KEY_ICON_COMPOUND_LOCATION, false),
                volume = pref(Preferences.KEY_ICON_COMPOUND_VOLUME, false),
                priority = pref(Preferences.KEY_ICON_COMPOUND_PRIORITY, "location,alarm_clock,zen,volume"),
                slotModes = slotModes,
                onChange = { key, value -> changed(key, value) }
            )

            CarrierSection(
                hideOne = pref(Preferences.KEY_ICON_HIDE_CARRIER_ONE, false),
                hideTwo = pref(Preferences.KEY_ICON_HIDE_CARRIER_TWO, false),
                hideHd = pref(Preferences.KEY_ICON_HIDE_CARRIER_HD, false),
                hideLsOne = pref(Preferences.KEY_ICON_HIDE_LS_CARRIER_ONE, false),
                hideLsTwo = pref(Preferences.KEY_ICON_HIDE_LS_CARRIER_TWO, false),
                onChange = { key, value -> changed(key, value) }
            )

            SlotsSection(
                slotModes = slotModes,
                commonSlots = commonSlots,
                slotModeOf = { slot -> pref(Preferences.slotKey(slot), 0) },
                ignoreSysHide = pref(Preferences.KEY_ICON_IGNORE_SYS_HIDE, false),
                hidePrivacy = pref(Preferences.KEY_ICON_HIDE_PRIVACY, false),
                regionSampling = pref(Preferences.KEY_STATUSBAR_REGION_SAMPLING, 0),
                onChange = { key, value -> changed(key, value) }
            )

            if (systemUiRestartPending) {
                SmallTitle(stringResource(R.string.icon_apply_title))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.icon_restart_systemui),
                        summary = stringResource(R.string.icon_restart_systemui_summary),
                        onClick = {
                            // The remote (LSPosed daemon) copy is written asynchronously; make
                            // sure SystemUI starts after the daemon has the latest values.
                            Preferences.flush()
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

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun StackedSignalSection(
    enabled: Boolean,
    scale: Float,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_stacked_signal_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                enabled,
                stringResource(R.string.icon_custom_signal_icon),
                stringResource(R.string.icon_custom_signal_icon_summary)
            ) { onChange(Preferences.KEY_ICON_STACKED_ENABLED, it) }
            if (enabled) {
                SliderRow(stringResource(R.string.icon_icon_scale), scale, 0.5f, 1.5f) {
                    onChange(Preferences.KEY_ICON_STACKED_SCALE, it)
                }
            }
        }
    }
}

@Composable
private fun LeftContainerSection(
    enabled: Boolean,
    zen: Boolean,
    volume: Boolean,
    hotspot: Boolean,
    alarmClock: Boolean,
    location: Boolean,
    bluetooth: Boolean,
    nfc: Boolean,
    vpn: Boolean,
    airplane: Boolean,
    headset: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_left_container_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                enabled,
                stringResource(R.string.icon_left_container_master),
                stringResource(R.string.icon_left_container_master_summary)
            ) { onChange(Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED, it) }
            if (enabled) {
                TunerSwitch(zen, stringResource(R.string.icon_left_zen)) {
                    onChange(Preferences.KEY_ICON_LEFT_ZEN, it)
                }
                TunerSwitch(volume, stringResource(R.string.icon_left_volume)) {
                    onChange(Preferences.KEY_ICON_LEFT_VOLUME, it)
                }
                TunerSwitch(hotspot, stringResource(R.string.icon_left_hotspot)) {
                    onChange(Preferences.KEY_ICON_LEFT_HOTSPOT, it)
                }
                TunerSwitch(alarmClock, stringResource(R.string.icon_left_alarm_clock)) {
                    onChange(Preferences.KEY_ICON_LEFT_ALARM_CLOCK, it)
                }
                TunerSwitch(location, stringResource(R.string.icon_left_location)) {
                    onChange(Preferences.KEY_ICON_LEFT_LOCATION, it)
                }
                TunerSwitch(bluetooth, stringResource(R.string.icon_left_bluetooth)) {
                    onChange(Preferences.KEY_ICON_LEFT_BLUETOOTH, it)
                }
                TunerSwitch(nfc, stringResource(R.string.icon_left_nfc)) {
                    onChange(Preferences.KEY_ICON_LEFT_NFC, it)
                }
                TunerSwitch(vpn, stringResource(R.string.icon_left_vpn)) {
                    onChange(Preferences.KEY_ICON_LEFT_VPN, it)
                }
                TunerSwitch(airplane, stringResource(R.string.icon_left_airplane)) {
                    onChange(Preferences.KEY_ICON_LEFT_AIRPLANE, it)
                }
                TunerSwitch(headset, stringResource(R.string.icon_left_headset)) {
                    onChange(Preferences.KEY_ICON_LEFT_HEADSET, it)
                }
            }
        }
    }
}

@Composable
private fun CellularSection(
    activity: Boolean,
    type: Boolean,
    roam: Boolean,
    smallRoam: Boolean,
    roamGlobal: Boolean,
    voWifi: Boolean,
    volte: Boolean,
    volteNoService: Boolean,
    speechHd: Boolean,
    hideNonDefaultSim: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_cellular_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                activity,
                stringResource(R.string.icon_hide_data_activity),
                stringResource(R.string.icon_hide_data_activity_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_ACTIVITY, it) }
            TunerSwitch(
                type,
                stringResource(R.string.icon_hide_network_type),
                stringResource(R.string.icon_hide_network_type_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_TYPE, it) }
            TunerSwitch(
                roam,
                stringResource(R.string.icon_hide_roam_indicator),
                stringResource(R.string.icon_hide_roam_indicator_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM, it) }
            TunerSwitch(
                smallRoam,
                stringResource(R.string.icon_hide_small_roam_indicator),
                stringResource(R.string.icon_hide_small_roam_indicator_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM, it) }
            TunerSwitch(
                roamGlobal,
                stringResource(R.string.icon_hide_roam_globally),
                stringResource(R.string.icon_hide_roam_globally_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL, it) }
            TunerSwitch(
                voWifi,
                stringResource(R.string.icon_hide_vowifi),
                stringResource(R.string.icon_hide_vowifi_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_VOWIFI, it) }
            TunerSwitch(
                volte,
                stringResource(R.string.icon_hide_volte),
                stringResource(R.string.icon_hide_volte_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE, it) }
            TunerSwitch(
                volteNoService,
                stringResource(R.string.icon_hide_volte_no_service),
                stringResource(R.string.icon_hide_volte_no_service_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, it) }
            TunerSwitch(
                speechHd,
                stringResource(R.string.icon_hide_hd_call),
                stringResource(R.string.icon_hide_hd_call_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, it) }
            TunerSwitch(
                hideNonDefaultSim,
                stringResource(R.string.icon_hide_non_default_sim_icon),
                stringResource(R.string.icon_hide_non_default_sim_icon_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_NON_DEFAULT_SIM, it) }
        }
    }
}

@Composable
private fun WifiSection(
    activity: Boolean,
    type: Boolean,
    hideConnected: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_wifi_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                activity,
                stringResource(R.string.icon_hide_wifi_activity),
                stringResource(R.string.icon_hide_wifi_activity_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_WIFI_ACTIVITY, it) }
            TunerSwitch(
                type,
                stringResource(R.string.icon_hide_wifi_standard),
                stringResource(R.string.icon_hide_wifi_standard_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_WIFI_TYPE, it) }
            TunerSwitch(
                hideConnected,
                stringResource(R.string.icon_hide_connected_wifi_icon),
                stringResource(R.string.icon_hide_connected_wifi_icon_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, it) }
        }
    }
}

@Composable
private fun CellularTypeSection(
    forceSingle: Boolean,
    useCustom: Boolean,
    customValue: String,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_cellular_type_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                forceSingle,
                stringResource(R.string.icon_cellular_type_single),
                stringResource(R.string.icon_cellular_type_single_summary)
            ) { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE, it) }
            TunerSwitch(
                useCustom,
                stringResource(R.string.icon_cellular_type_custom),
                stringResource(R.string.icon_cellular_type_custom_summary)
            ) { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM, it) }
            if (useCustom) {
                TextField(
                    value = customValue,
                    onValueChange = { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, it) },
                    label = stringResource(R.string.icon_cellular_type_custom_val),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CompoundSection(
    slotMode: Int,
    alarm: Boolean,
    zen: Boolean,
    location: Boolean,
    volume: Boolean,
    priority: String,
    slotModes: List<String>,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_compound_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_compound_slot_mode),
                summary = stringResource(R.string.icon_compound_slot_mode_summary),
                items = slotModes,
                selectedIndex = slotMode.coerceIn(0, slotModes.lastIndex),
                onSelectedIndexChange = { onChange(Preferences.slotKey("compound_icon"), it) }
            )
            if (slotMode in 1..3) {
                TunerSwitch(
                    alarm,
                    stringResource(R.string.icon_compound_alarm),
                    stringResource(R.string.icon_compound_alarm_summary)
                ) { onChange(Preferences.KEY_ICON_COMPOUND_ALARM, it) }
                TunerSwitch(
                    zen,
                    stringResource(R.string.icon_compound_zen),
                    stringResource(R.string.icon_compound_zen_summary)
                ) { onChange(Preferences.KEY_ICON_COMPOUND_ZEN, it) }
                TunerSwitch(
                    location,
                    stringResource(R.string.icon_compound_location),
                    stringResource(R.string.icon_compound_location_summary)
                ) { onChange(Preferences.KEY_ICON_COMPOUND_LOCATION, it) }
                TunerSwitch(
                    volume,
                    stringResource(R.string.icon_compound_volume),
                    stringResource(R.string.icon_compound_volume_summary)
                ) { onChange(Preferences.KEY_ICON_COMPOUND_VOLUME, it) }
                TextField(
                    value = priority,
                    onValueChange = { onChange(Preferences.KEY_ICON_COMPOUND_PRIORITY, it) },
                    label = stringResource(R.string.icon_compound_priority),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CarrierSection(
    hideOne: Boolean,
    hideTwo: Boolean,
    hideHd: Boolean,
    hideLsOne: Boolean,
    hideLsTwo: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_carrier_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                hideOne,
                stringResource(R.string.icon_hide_carrier_one),
                stringResource(R.string.icon_hide_carrier_one_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CARRIER_ONE, it) }
            TunerSwitch(
                hideTwo,
                stringResource(R.string.icon_hide_carrier_two),
                stringResource(R.string.icon_hide_carrier_two_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CARRIER_TWO, it) }
            TunerSwitch(
                hideHd,
                stringResource(R.string.icon_hide_carrier_hd),
                stringResource(R.string.icon_hide_carrier_hd_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CARRIER_HD, it) }
            TunerSwitch(
                hideLsOne,
                stringResource(R.string.icon_hide_ls_carrier_one),
                stringResource(R.string.icon_hide_ls_carrier_one_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_LS_CARRIER_ONE, it) }
            TunerSwitch(
                hideLsTwo,
                stringResource(R.string.icon_hide_ls_carrier_two),
                stringResource(R.string.icon_hide_ls_carrier_two_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_LS_CARRIER_TWO, it) }
        }
    }
}

@Composable
private fun SlotsSection(
    slotModes: List<String>,
    commonSlots: List<String>,
    slotModeOf: (String) -> Int,
    ignoreSysHide: Boolean,
    hidePrivacy: Boolean,
    regionSampling: Int,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_slots_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            commonSlots.forEach { slot ->
                val mode = slotModeOf(slot)
                OverlayDropdownPreference(
                    title = slot.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    items = slotModes,
                    selectedIndex = mode.coerceIn(0, slotModes.lastIndex),
                    onSelectedIndexChange = { index ->
                        onChange(Preferences.slotKey(slot), index)
                    }
                )
            }
            TunerSwitch(
                ignoreSysHide,
                stringResource(R.string.icon_ignore_system_hiding),
                stringResource(R.string.icon_ignore_system_hiding_summary)
            ) { onChange(Preferences.KEY_ICON_IGNORE_SYS_HIDE, it) }
            TunerSwitch(
                hidePrivacy,
                stringResource(R.string.icon_hide_privacy_indicator),
                stringResource(R.string.icon_hide_privacy_indicator_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_PRIVACY, it) }
            TunerSwitch(
                regionSampling != 0,
                stringResource(R.string.icon_region_sampling),
                stringResource(R.string.icon_region_sampling_summary)
            ) { onChange(Preferences.KEY_STATUSBAR_REGION_SAMPLING, if (it) 1 else 0) }
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, rangeStart: Float, rangeEnd: Float, onValue: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title)
        Slider(
            value = value.coerceIn(rangeStart, rangeEnd),
            onValueChange = onValue,
            valueRange = rangeStart..rangeEnd
        )
    }
}