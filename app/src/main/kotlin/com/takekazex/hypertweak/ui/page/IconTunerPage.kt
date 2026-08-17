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
        "mobile", "no_sim", "airplane", "wifi", "hotspot", "vpn", "network_speed",
        "bluetooth", "bluetooth_handsfree_battery", "handle_battery", "nfc", "gps",
        "location", "headset", "alarm_clock", "zen", "volume", "second_space"
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
                singleStyle = pref(Preferences.KEY_ICON_STACKED_SVG_SINGLE, 0),
                stackedStyle = pref(Preferences.KEY_ICON_STACKED_SVG_STACKED, 0),
                scale = pref(Preferences.KEY_ICON_STACKED_SCALE, 1f),
                paddingStart = pref(Preferences.KEY_ICON_STACKED_PADDING_START, 0f),
                paddingEnd = pref(Preferences.KEY_ICON_STACKED_PADDING_END, 0f),
                alphaFg = pref(Preferences.KEY_ICON_STACKED_ALPHA_FG, 1f),
                alphaBg = pref(Preferences.KEY_ICON_STACKED_ALPHA_BG, 1f),
                typeSize = pref(Preferences.KEY_ICON_STACKED_TYPE_SIZE, 11f),
                typeWeight = pref(Preferences.KEY_ICON_STACKED_TYPE_WEIGHT, 400),
                showSingle = pref(Preferences.KEY_ICON_STACKED_SHOW_SINGLE, false),
                showStacked = pref(Preferences.KEY_ICON_STACKED_SHOW_STACKED, false),
                showRoaming = pref(Preferences.KEY_ICON_STACKED_SHOW_ROAMING, false),
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

            WifiSection(
                activity = pref(Preferences.KEY_ICON_HIDE_WIFI_ACTIVITY, false),
                type = pref(Preferences.KEY_ICON_HIDE_WIFI_TYPE, false),
                hideConnected = pref(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false),
                onChange = { key, value -> changed(key, value) }
            )

            SlotsSection(
                slotModes = slotModes,
                commonSlots = commonSlots,
                slotModeOf = { slot -> pref(Preferences.slotKey(slot), 0) },
                ignoreSysHide = pref(Preferences.KEY_ICON_IGNORE_SYS_HIDE, false),
                hidePrivacy = pref(Preferences.KEY_ICON_HIDE_PRIVACY, false),
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
    singleStyle: Int,
    stackedStyle: Int,
    scale: Float,
    paddingStart: Float,
    paddingEnd: Float,
    alphaFg: Float,
    alphaBg: Float,
    typeSize: Float,
    typeWeight: Int,
    showSingle: Boolean,
    showStacked: Boolean,
    showRoaming: Boolean,
    onChange: (String, Any) -> Unit
) {
    val styles = listOf("HyperOS", "iOS", stringResource(R.string.icon_style_custom), "iOS 27")
    val weights = (100..900 step 100).map { "$it" }
    fun weightIndex(w: Int): Int = ((w - 100) / 100).coerceIn(0, weights.lastIndex)

    SmallTitle(stringResource(R.string.icon_stacked_signal_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                enabled,
                stringResource(R.string.icon_custom_signal_icon),
                stringResource(R.string.icon_custom_signal_icon_summary)
            ) { onChange(Preferences.KEY_ICON_STACKED_ENABLED, it) }
            if (enabled) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.icon_single_style),
                    items = styles,
                    selectedIndex = singleStyle.coerceIn(0, styles.lastIndex),
                    onSelectedIndexChange = { onChange(Preferences.KEY_ICON_STACKED_SVG_SINGLE, it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.icon_stacked_style),
                    items = styles,
                    selectedIndex = stackedStyle.coerceIn(0, styles.lastIndex),
                    onSelectedIndexChange = { onChange(Preferences.KEY_ICON_STACKED_SVG_STACKED, it) }
                )
                SliderRow(stringResource(R.string.icon_icon_scale), scale, 0.5f, 1.5f) {
                    onChange(Preferences.KEY_ICON_STACKED_SCALE, it)
                }
                SliderRow(stringResource(R.string.icon_padding_start), paddingStart, 0f, 12f) {
                    onChange(Preferences.KEY_ICON_STACKED_PADDING_START, it)
                }
                SliderRow(stringResource(R.string.icon_padding_end), paddingEnd, 0f, 12f) {
                    onChange(Preferences.KEY_ICON_STACKED_PADDING_END, it)
                }
                SliderRow(stringResource(R.string.icon_signal_alpha), alphaFg, 0.1f, 1f) {
                    onChange(Preferences.KEY_ICON_STACKED_ALPHA_FG, it)
                }
                SliderRow(stringResource(R.string.icon_second_row_alpha), alphaBg, 0.1f, 1f) {
                    onChange(Preferences.KEY_ICON_STACKED_ALPHA_BG, it)
                }
                SliderRow(stringResource(R.string.icon_type_text_size), typeSize, 6f, 20f) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_SIZE, it)
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.icon_type_text_weight),
                    items = weights,
                    selectedIndex = weightIndex(typeWeight),
                    onSelectedIndexChange = { onChange(Preferences.KEY_ICON_STACKED_TYPE_WEIGHT, it * 100 + 100) }
                )
                TunerSwitch(
                    showSingle,
                    stringResource(R.string.icon_single_sim_icon),
                    stringResource(R.string.icon_single_sim_icon_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_SHOW_SINGLE, it) }
                TunerSwitch(
                    showStacked,
                    stringResource(R.string.icon_stacked_dual_sim),
                    stringResource(R.string.icon_stacked_dual_sim_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_SHOW_STACKED, it) }
                TunerSwitch(
                    showRoaming,
                    stringResource(R.string.icon_show_roaming),
                    stringResource(R.string.icon_show_roaming_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_SHOW_ROAMING, it) }
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
private fun SlotsSection(
    slotModes: List<String>,
    commonSlots: List<String>,
    slotModeOf: (String) -> Int,
    ignoreSysHide: Boolean,
    hidePrivacy: Boolean,
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