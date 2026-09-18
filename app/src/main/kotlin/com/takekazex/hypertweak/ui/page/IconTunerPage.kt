package com.takekazex.hypertweak.ui.page

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicyConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.NotificationIconLimit
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoLayout
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
private val iconPrefsSaver = Saver<SnapshotStateMap<String, Any>, ArrayList<Any>>(
    save = { state ->
        ArrayList<Any>(state.size * 2).apply {
            state.forEach { (key, value) ->
                add(key)
                add(value)
            }
        }
    },
    restore = { values ->
        mutableStateMapOf<String, Any>().apply {
            values.asSequence().chunked(2).forEach { entry ->
                if (entry.size == 2 && entry[0] is String) put(entry[0] as String, entry[1])
            }
        }
    }
)

/**
 * Status-bar icon customization, ported from Hyper Helper's icon tuner
 * (see the reverse-engineering workspace, cache/xiaomihelper-2bfd4873a4138764, and
 * OS4_ADAPTATION_PLAN.md). State is kept locally rather than hoisted into `MainActivity`, so every
 * change reports SystemUI to the shared Home restart dialog; the page also keeps its local restart
 * affordance. The page is a second-level settings menu: related controls are
 * grouped behind a compact TabRow so the long list does not become one undifferentiated scroll.
 */

@Composable
private fun TunerSwitch(
    checked: Boolean,
    title: String,
    summary: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = title,
        summary = summary,
        enabled = enabled
    )
}

@Composable
private fun TunerSwitch(
    checked: Boolean,
    title: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = title,
        enabled = enabled
    )
}

@Composable
fun IconTunerPage(onBack: () -> Unit, onNavigateToIconOrder: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }

    // Local remember-backed state so toggles update instantly; the remote preference write is
    // async, so reading Preferences directly in composition made switches bounce back.
    val prefs = rememberSaveable(saver = iconPrefsSaver) {
        mutableStateMapOf<String, Any>()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> pref(key: String, default: T): T {
        val existing = prefs[key]
        if (existing != null) {
            val restored = if (default is Set<*> && existing is List<*>) {
                existing.filterIsInstance<String>().toSet()
            } else {
                existing
            }
            return restored as T
        }
        // Load the stored value once; defaults are only for keys never written.
        val stored: T = when (default) {
            is Boolean -> Preferences.getBoolean(key, default as Boolean) as T
            is Int -> Preferences.getInt(key, default as Int) as T
            is Float -> Preferences.getFloat(key, default as Float) as T
            is String -> Preferences.getString(key, default as String) as T
            is Set<*> -> Preferences.getStringSet(
                key,
                default.filterIsInstance<String>().toSet()
            ) as T
            else -> default
        }
        prefs[key] = if (stored is Set<*>) {
            ArrayList(stored.filterIsInstance<String>())
        } else {
            stored as Any
        }
        return stored
    }

    fun changed(key: String, value: Any) {
        requestRestartScopes(RestartScopeSelection(systemUi = true))
        prefs[key] = if (value is Set<*>) {
            ArrayList(value.filterIsInstance<String>())
        } else {
            value
        }
        when (value) {
            is Boolean -> Preferences.putBoolean(key, value)
            is Int -> Preferences.putInt(key, value)
            is Float -> Preferences.putFloat(key, value)
            is String -> Preferences.putString(key, value)
            is Set<*> -> Preferences.putStringSet(key, value.filterIsInstance<String>().toSet())
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

    val legacyLeftEnabled = pref(Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED, false)
    val leftMode = pref(
        IconTunerOptions.KEY_LEFT_MODE,
        if (Preferences.contains(IconTunerOptions.KEY_LEFT_MODE)) {
            Preferences.getInt(IconTunerOptions.KEY_LEFT_MODE, IconTunerOptions.LEFT_MODE_DISABLED)
        } else if (legacyLeftEnabled) {
            IconTunerOptions.LEFT_MODE_HOME
        } else {
            IconTunerOptions.LEFT_MODE_DISABLED
        }
    )

    // The preview and the 布局 tab read the same left-placement toggles, so the map is built once
    // here instead of inside the section.
    val leftSelected = IconTunerOptions.leftPreferenceKeys.associateWith { key -> pref(key, false) }
    val previewModel = buildStatusBarPreview(
        StatusBarPreviewInput(
            position = pref(IconTunerOptions.KEY_POSITION, IconSlotPolicyConfig.POSITION_SYSTEM),
            customOrder = pref(IconTunerOptions.KEY_POSITION_VALUES, emptySet<String>()),
            reorderHidden = pref(IconTunerOptions.KEY_POSITION_REORDER, false),
            slotModes = IconSlotCatalog.slots.associateWith { pref(Preferences.slotKey(it), 0) },
            extraHidden = IconSlotPolicy.parseSlotList(
                pref(Preferences.KEY_ICON_EXT_BLOCKED, "")
            ).toSet(),
            leftSlots = if (leftMode == IconTunerOptions.LEFT_MODE_DISABLED) {
                emptySet()
            } else {
                IconTunerOptions.leftPreferenceKeys
                    .filter { leftSelected[it] == true }
                    .flatMap { IconTunerOptions.slotsForLeftPreference(it) }
                    .toSet()
            },
            stackedEnabled = pref(Preferences.KEY_ICON_STACKED_ENABLED, false),
            duoEnabled = pref(Preferences.KEY_ICON_DUO_ENABLED, false),
            duoSizeDp = pref(
                Preferences.KEY_ICON_DUO_SIZE,
                DuoLayout.DEFAULT_ICON_SIZE_DP
            ).toFloat(),
            hideMobileOnWifi = pref(Preferences.KEY_ICON_HIDE_MOBILE_ON_WIFI, false),
            hideWifiConnected = pref(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false)
        )
    )
    val showCellularType = !pref(Preferences.KEY_ICON_HIDE_CELLULAR_TYPE, false)

    val categories = listOf(
        stringResource(R.string.icon_tuner_tab_layout),
        stringResource(R.string.icon_tuner_tab_mobile),
        stringResource(R.string.icon_tuner_tab_wifi),
        stringResource(R.string.icon_tuner_tab_other)
    )
    // The page backdrop is already Miuix `surface`, which is also TabRowWithContour's default
    // outer color. Use the container surface for the contour so the tab bar keeps a visible base.
    val tabRowColors = TabRowDefaults.tabRowColors(
        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        selectedBackgroundColor = MiuixTheme.colorScheme.surface
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
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))
            StatusBarPreview(model = previewModel, showCellularType = showCellularType)
            TabRowWithContour(
                tabs = categories,
                selectedTabIndex = selectedCategory.coerceIn(0, categories.lastIndex),
                onTabSelected = { selectedCategory = it },
                colors = tabRowColors,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Column(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))

                when (selectedCategory.coerceIn(0, categories.lastIndex)) {
                    0 -> {
                        PositionSection(onNavigateToIconOrder = onNavigateToIconOrder)
                        LeftContainerSection(
                            mode = leftMode,
                            leftSelected = leftSelected,
                            onChange = { key, value ->
                                changed(key, value)
                                if (key == IconTunerOptions.KEY_LEFT_MODE && value is Int) {
                                    changed(Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED, value != 0)
                                }
                            }
                        )
                    }
                    1 -> {
                        DuoSignalSection(
                            enabled = pref(Preferences.KEY_ICON_DUO_ENABLED, false),
                            expanded = pref(Preferences.KEY_ICON_DUO_EXPANDED, 1),
                            sizeDp = pref(
                                Preferences.KEY_ICON_DUO_SIZE,
                                DuoLayout.DEFAULT_ICON_SIZE_DP
                            ),
                            onChange = { key, value -> changed(key, value) }
                        )
                        StackedSignalSection(
                            enabled = pref(Preferences.KEY_ICON_STACKED_ENABLED, false),
                            hideWhenDisconnected = pref(
                                Preferences.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT,
                                false
                            ),
                            hideWhenWifi = pref(Preferences.KEY_ICON_STACKED_TYPE_HIDE_WIFI, false),
                            showSingleBadge = pref(Preferences.KEY_ICON_STACKED_TYPE_SHOW_SINGLE, false),
                            showStackedBadge = pref(Preferences.KEY_ICON_STACKED_TYPE_SHOW_STACKED, false),
                            showRoaming = pref(Preferences.KEY_ICON_STACKED_TYPE_ROAMING, false),
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
                hideSimOne = pref(Preferences.KEY_ICON_HIDE_SIM_ONE, false),
                hideSimTwo = pref(Preferences.KEY_ICON_HIDE_SIM_TWO, false),
                hideOnWifi = pref(Preferences.KEY_ICON_HIDE_MOBILE_ON_WIFI, false),
                onChange = { key, value ->
                    changed(key, value)
                }
                        )
                        CellularTypeSection(
                forceSingle = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE, false),
                useCustom = pref(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM, false),
                customValue = pref(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, ""),
                swapSingle = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP, false),
                singleSizeEnabled = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE, false),
                singleSize = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE_VAL, 14f),
                drawableFontEnabled = pref(Preferences.KEY_ICON_FONT_MOBILE_TYPE, false),
                drawableWeight = pref(Preferences.KEY_ICON_FONT_MOBILE_TYPE_WEIGHT, 660),
                singleFontEnabled = pref(Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE, false),
                singleWeight = pref(Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE_WEIGHT, 400),
                onChange = { key, value -> changed(key, value) }
                        )
                    }
                    2 -> WifiSection(
                        activity = pref(Preferences.KEY_ICON_HIDE_WIFI_ACTIVITY, false),
                        type = pref(Preferences.KEY_ICON_HIDE_WIFI_TYPE, false),
                        hideConnected = pref(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false),
                        activityRight = pref(Preferences.KEY_ICON_WIFI_ACTIVITY_RIGHT, false),
                        standardMode = pref(Preferences.KEY_ICON_WIFI_STANDARD_MODE, 0),
                        standardMap = pref(Preferences.KEY_ICON_WIFI_STANDARD_MAP, "4,5,6,7,8"),
                        paddingEnabled = pref(Preferences.KEY_ICON_WIFI_PADDING, false),
                        paddingStart = pref(Preferences.KEY_ICON_WIFI_PADDING_START_VAL, 0f),
                        paddingEnd = pref(Preferences.KEY_ICON_WIFI_PADDING_END_VAL, 0f),
                        onChange = { key, value -> changed(key, value) }
                    )
                    3 -> {
                        NotificationSection(
                enabled = pref(Preferences.KEY_STATUSBAR_NOTIFICATION_MAX, false),
                maximum = NotificationIconLimit.clamp(
                    pref(
                        Preferences.KEY_STATUSBAR_NOTIFICATION_ICON_MAX,
                        NotificationIconLimit.DEFAULT
                    )
                ),
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
                ccCarrierLeft = pref(Preferences.KEY_CC_CARRIER_LEFT, false),
                ccHideDate = pref(Preferences.KEY_CC_HIDE_DATE, false),
                ccTwoLine = pref(Preferences.KEY_CC_CARRIER_TWO_LINE, false),
                ccShowNonDataType = pref(Preferences.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE, false),
                onChange = { key, value -> changed(key, value) }
                        )
                        SlotsSection(
                slotModes = slotModes,
                slotModeOf = { slot -> pref(Preferences.slotKey(slot), 0) },
                ignoreSysHide = pref(Preferences.KEY_ICON_IGNORE_SYS_HIDE, false),
                hidePrivacy = pref(Preferences.KEY_ICON_HIDE_PRIVACY, false),
                regionSampling = pref(Preferences.KEY_STATUSBAR_REGION_SAMPLING, 0),
                onChange = { key, value -> changed(key, value) }
                        )
                    }
                }

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
                            handleRestartedScopes(RestartScopeSelection(systemUi = true))
                            systemUiRestartPending = false
                        }
                    )
                }
                }

                Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
            }
        }
    }
}

@Composable
private fun StackedSignalSection(
    enabled: Boolean,
    hideWhenDisconnected: Boolean,
    hideWhenWifi: Boolean,
    showSingleBadge: Boolean,
    showStackedBadge: Boolean,
    showRoaming: Boolean,
    onChange: (String, Any) -> Unit
) {
    // The signal artwork is fixed to the built-in HyperOS 3 SVGs, and icon scale, opacity, signal
    // padding, SVG import and every type-appearance knob are no longer exposed here, so the hook
    // renders its built-in values for them.
    SmallTitle(stringResource(R.string.icon_stacked_signal_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                enabled,
                stringResource(R.string.icon_stacked_signal_style),
                stringResource(R.string.icon_stacked_signal_style_summary)
            ) { onChange(Preferences.KEY_ICON_STACKED_ENABLED, it) }
            if (enabled) {
                TunerSwitch(
                    hideWhenDisconnected,
                    stringResource(R.string.icon_stacked_type_hide_disconnect),
                    stringResource(R.string.icon_stacked_type_hide_disconnect_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, it) }
                TunerSwitch(
                    hideWhenWifi,
                    stringResource(R.string.icon_stacked_type_hide_wifi),
                    stringResource(R.string.icon_stacked_type_hide_wifi_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_HIDE_WIFI, it) }
                TunerSwitch(
                    showSingleBadge,
                    stringResource(R.string.icon_stacked_type_single_badge),
                    stringResource(R.string.icon_stacked_type_single_badge_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_SHOW_SINGLE, it) }
                TunerSwitch(
                    showStackedBadge,
                    stringResource(R.string.icon_stacked_type_stacked_badge),
                    stringResource(R.string.icon_stacked_type_stacked_badge_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_SHOW_STACKED, it) }
                TunerSwitch(
                    showRoaming,
                    stringResource(R.string.icon_stacked_type_roaming),
                    stringResource(R.string.icon_stacked_type_roaming_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_ROAMING, it) }
            }
        }
    }
}

/**
 * Left-placement rows: the preference key, its label, and the slots it moves.
 *
 * [previewSlot] is resolved from [IconTunerOptions.slotsForLeftPreference] so the row cannot show a
 * different glyph than the hook's actual slot group, and [LeftContainerSectionTest] keeps the list
 * in step with [IconTunerOptions.leftPreferenceKeys].
 */
internal data class LeftToggleRow(
    val key: String,
    @StringRes val labelRes: Int,
    /** Representative slot for the preview; the compound group names no host slot of its own. */
    val previewSlot: String
)

internal val LEFT_TOGGLE_ROWS: List<LeftToggleRow> = listOf(
    LeftToggleRow(Preferences.KEY_ICON_LEFT_ZEN, R.string.icon_left_zen, "zen"),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_VOLUME, R.string.icon_left_volume, "volume"),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_HOTSPOT, R.string.icon_left_hotspot, "hotspot"),
    LeftToggleRow(
        Preferences.KEY_ICON_LEFT_ALARM_CLOCK,
        R.string.icon_left_alarm_clock,
        "alarm_clock"
    ),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_LOCATION, R.string.icon_left_location, "location"),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_BLUETOOTH, R.string.icon_left_bluetooth, "bluetooth"),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_NFC, R.string.icon_left_nfc, "nfc"),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_VPN, R.string.icon_left_vpn, "vpn"),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_AIRPLANE, R.string.icon_left_airplane, "airplane"),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_HEADSET, R.string.icon_left_headset, "headset"),
    LeftToggleRow(Preferences.KEY_ICON_LEFT_COMPOUND, R.string.icon_compound_title, "compound_icon")
)

/**
 * 图标左置.
 *
 * The container is the left region of the bar — `phone_status_bar_left_container`, the row the
 * clock and the notification icons already share — and the hook inserts it right after the clock
 * (`indexOfChild(clock) + 1`). So the icons land where MIUI shows notification icons, which is what
 * "left placement" means; the moved icons are hidden from the right cluster instead.
 *
 * The scope dropdown is the only gate. This card used to carry a second "将图标移至时间旁" switch
 * whose checked state *was* `mode != OFF`; turning it back on forced the Home-only scope, so a user
 * who had chosen 主屏和锁屏 silently lost the lockscreen half. The dropdown's own 关闭 already
 * means off, so the switch is gone.
 */
@Composable
private fun LeftContainerSection(
    mode: Int,
    leftSelected: Map<String, Boolean>,
    onChange: (String, Any) -> Unit
) {
    val modes = listOf(
        stringResource(R.string.icon_left_mode_disabled),
        stringResource(R.string.icon_left_mode_home),
        stringResource(R.string.icon_left_mode_both)
    )
    SmallTitle(stringResource(R.string.icon_left_container_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_left_container_mode),
                summary = stringResource(R.string.icon_left_container_mode_summary),
                items = modes,
                selectedIndex = mode.coerceIn(0, modes.lastIndex),
                onSelectedIndexChange = { onChange(IconTunerOptions.KEY_LEFT_MODE, it) }
            )
            if (mode != IconTunerOptions.LEFT_MODE_DISABLED) {
                Text(
                    text = stringResource(R.string.icon_left_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LEFT_TOGGLE_ROWS.forEach { row ->
                    val info = IconSlotCatalog.of(row.previewSlot)
                    SwitchPreference(
                        checked = leftSelected[row.key] == true,
                        onCheckedChange = { onChange(row.key, it) },
                        title = stringResource(row.labelRes),
                        startAction = {
                            Icon(
                                painter = painterResource(
                                    id = info?.iconRes ?: IconSlotCatalog.fallbackIconRes()
                                ),
                                // The row title already names the icon group.
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp).size(22.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        }
                    )
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
    hideSimOne: Boolean,
    hideSimTwo: Boolean,
    hideOnWifi: Boolean,
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
            TunerSwitch(
                hideSimOne,
                stringResource(R.string.icon_hide_sim_one),
                stringResource(R.string.icon_hide_sim_one_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_SIM_ONE, it) }
            TunerSwitch(
                hideSimTwo,
                stringResource(R.string.icon_hide_sim_two),
                stringResource(R.string.icon_hide_sim_two_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_SIM_TWO, it) }
            TunerSwitch(
                hideOnWifi,
                stringResource(R.string.icon_hide_mobile_on_wifi),
                stringResource(R.string.icon_hide_mobile_on_wifi_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_MOBILE_ON_WIFI, it) }
        }
    }
}

@Composable
private fun WifiSection(
    activity: Boolean,
    type: Boolean,
    hideConnected: Boolean,
    activityRight: Boolean,
    standardMode: Int,
    standardMap: String,
    paddingEnabled: Boolean,
    paddingStart: Float,
    paddingEnd: Float,
    onChange: (String, Any) -> Unit
) {
    val standardModes = listOf(
        stringResource(R.string.icon_wifi_standard_follow),
        stringResource(R.string.icon_wifi_standard_hide),
        stringResource(R.string.icon_wifi_standard_native),
        stringResource(R.string.icon_wifi_standard_custom)
    )
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
            TunerSwitch(
                activityRight,
                stringResource(R.string.icon_wifi_activity_right),
                stringResource(R.string.icon_wifi_activity_right_summary)
            ) { onChange(Preferences.KEY_ICON_WIFI_ACTIVITY_RIGHT, it) }
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_wifi_standard_mode),
                summary = stringResource(R.string.icon_wifi_standard_mode_summary),
                items = standardModes,
                selectedIndex = (standardMode - 0).coerceIn(0, standardModes.lastIndex),
                onSelectedIndexChange = { onChange(Preferences.KEY_ICON_WIFI_STANDARD_MODE, it) }
            )
            if (standardMode == 3) {
                TextField(
                    value = standardMap,
                    onValueChange = { onChange(Preferences.KEY_ICON_WIFI_STANDARD_MAP, it) },
                    label = stringResource(R.string.icon_wifi_standard_map),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            TunerSwitch(
                paddingEnabled,
                stringResource(R.string.icon_wifi_padding),
                stringResource(R.string.icon_wifi_padding_summary)
            ) { onChange(Preferences.KEY_ICON_WIFI_PADDING, it) }
            if (paddingEnabled) {
                SliderRow(stringResource(R.string.icon_wifi_padding_start), paddingStart, 0f, 24f) {
                    onChange(Preferences.KEY_ICON_WIFI_PADDING_START_VAL, it)
                }
                SliderRow(stringResource(R.string.icon_wifi_padding_end), paddingEnd, 0f, 24f) {
                    onChange(Preferences.KEY_ICON_WIFI_PADDING_END_VAL, it)
                }
            }
        }
    }
}

/**
 * Entry row for the order editor. The controls themselves live on [IconOrderPage], which renders
 * the same slot catalog as [SlotsSection]; the two comma-separated host-slot fields this row used
 * to expose were removed because they required the internal wire names to be known by hand.
 *
 * The current mode is read live instead of through the page's local preference cache: the sub-page
 * writes the same key, and a remembered copy would show the value from before the visit.
 */
@Composable
private fun PositionSection(onNavigateToIconOrder: () -> Unit) {
    val positions = listOf(
        stringResource(R.string.icon_position_system),
        stringResource(R.string.icon_position_wifi_before_mobile),
        stringResource(R.string.icon_position_custom)
    )
    val position = Preferences.getInt(
        IconTunerOptions.KEY_POSITION,
        IconSlotPolicyConfig.POSITION_SYSTEM
    )
    SmallTitle(stringResource(R.string.icon_position_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        ArrowPreference(
            title = stringResource(R.string.icon_position_title),
            summary = positions[position.coerceIn(0, positions.lastIndex)],
            onClick = onNavigateToIconOrder
        )
    }
}

@Composable
private fun NotificationSection(
    enabled: Boolean,
    maximum: Int,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_notification_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                enabled,
                stringResource(R.string.icon_notification_max),
                stringResource(R.string.icon_notification_max_summary)
            ) { onChange(Preferences.KEY_STATUSBAR_NOTIFICATION_MAX, it) }
            if (enabled) {
                NotificationMaxIconsRow(maximum) {
                    onChange(Preferences.KEY_STATUSBAR_NOTIFICATION_ICON_MAX, it)
                }
            }
        }
    }
}

/**
 * Notification-icon limit: a discrete slider plus the same tap-to-type dialog the
 * interface-scale row uses.
 *
 * The spec is minimum 0, default 3, maximum 15, all taken from [NotificationIconLimit] so the row
 * and [com.takekazex.hypertweak.hook.rules.systemui.icon.NotificationMaxNumberHooker] cannot drift.
 *
 * The slider snaps to whole icons because Miuix's `steps` resolves the dragged fraction through
 * `round(fraction * (steps + 1))`; [NotificationIconLimit.sliderSteps] derives the count that makes
 * every integer in the range reachable. A slider alone cannot hit an exact value reliably, which is
 * why the row also opens a text field.
 */
@Composable
private fun NotificationMaxIconsRow(maximum: Int, onValueChange: (Int) -> Unit) {
    val current = NotificationIconLimit.clamp(maximum)
    var sliderValue by remember(current) { mutableFloatStateOf(current.toFloat()) }
    var expanded by remember { mutableStateOf(false) }
    ArrowPreference(
        title = stringResource(R.string.icon_notification_max_value),
        summary = stringResource(R.string.icon_notification_max_range),
        endActions = {
            Text(
                text = sliderValue.roundToInt().toString(),
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        },
        onClick = { expanded = !expanded },
        holdDownState = expanded,
        bottomAction = {
            Slider(
                value = sliderValue.coerceIn(
                    NotificationIconLimit.MIN.toFloat(),
                    NotificationIconLimit.MAX.toFloat()
                ),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onValueChange(sliderValue.roundToInt()) },
                valueRange = NotificationIconLimit.MIN.toFloat()..NotificationIconLimit.MAX.toFloat(),
                steps = NotificationIconLimit.sliderSteps(),
                showKeyPoints = true,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }
    )
    IntValueDialog(
        show = expanded,
        title = stringResource(R.string.icon_notification_max_value),
        summary = stringResource(R.string.icon_notification_max_range),
        suffix = null,
        range = NotificationIconLimit.RANGE,
        currentValue = { current },
        // The minimum (0) is a meaningful choice here — keep no notification icons — so a blank
        // field commits MIN rather than the current value.
        emptyValue = NotificationIconLimit.MIN,
        onValueConfirmed = onValueChange,
        onDismissRequest = { expanded = false }
    )
}

@Composable
private fun CellularTypeSection(
    forceSingle: Boolean,
    useCustom: Boolean,
    customValue: String,
    swapSingle: Boolean,
    singleSizeEnabled: Boolean,
    singleSize: Float,
    drawableFontEnabled: Boolean,
    drawableWeight: Int,
    singleFontEnabled: Boolean,
    singleWeight: Int,
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
            TunerSwitch(
                swapSingle,
                stringResource(R.string.icon_cellular_type_swap),
                stringResource(R.string.icon_cellular_type_swap_summary)
            ) { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP, it) }
            TunerSwitch(
                singleSizeEnabled,
                stringResource(R.string.icon_cellular_type_size),
                stringResource(R.string.icon_cellular_type_size_summary)
            ) { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE, it) }
            if (singleSizeEnabled) {
                SliderRow(stringResource(R.string.icon_cellular_type_size_value), singleSize, 6f, 24f) {
                    onChange(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE_VAL, it)
                }
            }
            TunerSwitch(
                drawableFontEnabled,
                stringResource(R.string.icon_cellular_type_font),
                stringResource(R.string.icon_cellular_type_font_summary)
            ) { onChange(Preferences.KEY_ICON_FONT_MOBILE_TYPE, it) }
            if (drawableFontEnabled) {
                IntSliderRow(stringResource(R.string.icon_font_weight), drawableWeight, 100, 900) {
                    onChange(Preferences.KEY_ICON_FONT_MOBILE_TYPE_WEIGHT, it)
                }
            }
            TunerSwitch(
                singleFontEnabled,
                stringResource(R.string.icon_cellular_type_single_font),
                stringResource(R.string.icon_cellular_type_single_font_summary)
            ) { onChange(Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE, it) }
            if (singleFontEnabled) {
                IntSliderRow(stringResource(R.string.icon_font_weight), singleWeight, 100, 900) {
                    onChange(Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE_WEIGHT, it)
                }
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
    ccCarrierLeft: Boolean,
    ccHideDate: Boolean,
    ccTwoLine: Boolean,
    ccShowNonDataType: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_carrier_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            // 开关 1: control-center carrier label on the leading edge.
            TunerSwitch(
                ccCarrierLeft,
                stringResource(R.string.icon_cc_carrier_left),
                stringResource(R.string.icon_cc_carrier_left_summary)
            ) { onChange(Preferences.KEY_CC_CARRIER_LEFT, it) }
            // 开关 2: hide the control-center date; it is also the enabling switch of 开关 3/4.
            TunerSwitch(
                ccHideDate,
                stringResource(R.string.icon_cc_hide_date),
                stringResource(R.string.icon_cc_hide_date_summary)
            ) { onChange(Preferences.KEY_CC_HIDE_DATE, it) }
            TunerSwitch(
                ccTwoLine,
                stringResource(R.string.icon_cc_carrier_two_line),
                stringResource(R.string.icon_cc_carrier_two_line_summary),
                enabled = ccHideDate
            ) { onChange(Preferences.KEY_CC_CARRIER_TWO_LINE, it) }
            TunerSwitch(
                ccShowNonDataType,
                stringResource(R.string.icon_cc_carrier_show_non_data_type),
                stringResource(R.string.icon_cc_carrier_show_non_data_type_summary),
                enabled = ccHideDate && ccTwoLine
            ) { onChange(Preferences.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE, it) }
            TunerSwitch(
                hideOne,
                stringResource(R.string.icon_hide_carrier_one),
                stringResource(if (ccHideDate && ccTwoLine) R.string.icon_cc_carrier_name_priority
                    else R.string.icon_hide_carrier_one_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CARRIER_ONE, it) }
            TunerSwitch(
                hideTwo,
                stringResource(R.string.icon_hide_carrier_two),
                stringResource(if (ccHideDate && ccTwoLine) R.string.icon_cc_carrier_name_priority
                    else R.string.icon_hide_carrier_two_summary)
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
    slotModeOf: (String) -> Int,
    ignoreSysHide: Boolean,
    hidePrivacy: Boolean,
    regionSampling: Int,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_slots_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            IconSlotCatalog.slots.forEach { slot ->
                val mode = slotModeOf(slot)
                val info = IconSlotCatalog.of(slot)
                OverlayDropdownPreference(
                    title = if (info != null) {
                        stringResource(info.labelRes)
                    } else {
                        IconSlotCatalog.fallbackLabel(slot)
                    },
                    items = slotModes,
                    selectedIndex = mode.coerceIn(0, slotModes.lastIndex),
                    onSelectedIndexChange = { index ->
                        onChange(Preferences.slotKey(slot), index)
                    },
                    startAction = {
                        Icon(
                            painter = painterResource(
                                id = info?.iconRes ?: IconSlotCatalog.fallbackIconRes()
                            ),
                            // The row title already names the slot, so the preview stays decorative.
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp).size(22.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
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

@Composable
private fun IntSliderRow(title: String, value: Int, rangeStart: Int, rangeEnd: Int, onValue: (Int) -> Unit) {
    SliderRow(title, value.toFloat(), rangeStart.toFloat(), rangeEnd.toFloat()) {
        onValue(it.roundToInt().coerceIn(rangeStart, rangeEnd))
    }
}

@Composable
private fun DuoSignalSection(enabled: Boolean, expanded: Int, sizeDp: Int, onChange: (String, Any) -> Unit) {
    SmallTitle(stringResource(R.string.icon_duo_title))
    DuoSignalPreview()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(enabled, stringResource(R.string.icon_duo_enabled),
                stringResource(R.string.icon_duo_summary)) { onChange(Preferences.KEY_ICON_DUO_ENABLED, it) }
            if (enabled) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.icon_duo_expanded),
                    items = listOf(stringResource(R.string.icon_duo_keep), stringResource(R.string.icon_duo_native)),
                    selectedIndex = expanded.coerceIn(0, 1),
                    onSelectedIndexChange = { onChange(Preferences.KEY_ICON_DUO_EXPANDED, it) }
                )
                DuoSizeRow(sizeDp) { onChange(Preferences.KEY_ICON_DUO_SIZE, it) }
            }
        }
    }
}

/**
 * Duo glyph size in dp.
 *
 * The slider is centred on the 24dp default and snaps back to it, so the untouched size is a
 * detent. Tapping the value opens [IntValueDialog] for an exact number, because a slider cannot
 * reach an arbitrary dp on a narrow screen.
 */
@Composable
private fun DuoSizeRow(sizeDp: Int, onChange: (Int) -> Unit) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { showDialog = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.icon_duo_size))
            Text(stringResource(R.string.icon_duo_size_value, sizeDp))
        }
        Slider(
            value = sizeDp.toFloat().coerceIn(
                DuoLayout.MIN_ICON_SIZE_DP.toFloat(),
                DuoLayout.MAX_ICON_SIZE_DP.toFloat()
            ),
            onValueChange = { onChange(DuoLayout.snapSizeDp(it)) },
            valueRange = DuoLayout.MIN_ICON_SIZE_DP.toFloat()..DuoLayout.MAX_ICON_SIZE_DP.toFloat()
        )
    }
    IntValueDialog(
        show = showDialog,
        title = stringResource(R.string.icon_duo_size),
        summary = stringResource(R.string.icon_duo_size_summary),
        suffix = stringResource(R.string.icon_duo_size_unit),
        range = DuoLayout.MIN_ICON_SIZE_DP..DuoLayout.MAX_ICON_SIZE_DP,
        currentValue = { sizeDp },
        // A blank field keeps the current size instead of jumping to the minimum.
        emptyValue = sizeDp,
        onValueConfirmed = onChange,
        onDismissRequest = { showDialog = false }
    )
}
