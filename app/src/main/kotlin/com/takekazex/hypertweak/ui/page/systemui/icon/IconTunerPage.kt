package com.takekazex.hypertweak.ui.page.systemui.icon

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoSizes
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.settings.contract.MobileTypeTypography
import com.takekazex.hypertweak.settings.contract.NotificationIconLimit
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SignalStyle
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.settings.contract.SignalWeight
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.components.slider.SliderEditState
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import com.takekazex.hypertweak.ui.effect.AppOverlayDialog
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private val iconPrefsSaver = Saver<SnapshotStateMap<String, Any>, ArrayList<Any>>(
    save = { IconTunerPreferenceState.save(it) },
    restore = { payload ->
        mutableStateMapOf<String, Any>().apply { putAll(IconTunerPreferenceState.restore(payload)) }
    }
)

/**
 * Status-bar icon customization, ported from Hyper Helper's icon tuner
 * (see the reverse-engineering workspace, cache/xiaomihelper-2bfd4873a4138764, and
 * OS4_ADAPTATION_PLAN.md). Local state updates immediately; SystemUI consumes remote settings
 * and refreshes the existing status-bar/control-center hosts. Controls are grouped behind a
 * Miuix [TabRowWithContour] (layout, mobile, Wi-Fi, other) so the list stays short.
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

/** A text edit stays local until the user confirms the complete value. */
@Composable
private fun IconTextPreference(
    title: String,
    value: String,
    enabled: Boolean,
    hint: String? = null,
    onConfirm: (String) -> Unit
) {
    val epoch = LocalAppSettingsStore.current.backup.settingsEpochForUi()
    key(epoch, value) {
        var editing by rememberSaveable { mutableStateOf(false) }
        var draft by rememberSaveable { mutableStateOf(value) }
        ArrowPreference(title = title, summary = value.ifEmpty { null }, enabled = enabled, onClick = {
            draft = value
            editing = true
        })
        AppOverlayDialog(
            show = enabled && editing,
            title = title,
            onDismissRequest = { editing = false },
            content = {
                hint?.let { Text(it, Modifier.padding(bottom = 12.dp)) }
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.scale_cancel),
                        onClick = { editing = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.scale_ok),
                        onClick = {
                            editing = false
                            if (draft != value) onConfirm(draft)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }
}

@Composable
fun IconTunerPage(onBack: () -> Unit, onNavigateToIconOrder: () -> Unit, onNavigateToIconRows: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current
    val scrollBehavior = MiuixScrollBehavior()
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }

    // Local remember-backed state so toggles update instantly; the remote preference write is
    // async, so reading Preferences directly in composition made switches bounce back.
    val prefs = rememberSaveable(saver = iconPrefsSaver) {
        mutableStateMapOf<String, Any>()
    }

    // Epoch changes invalidate saved page values after reset/restore, including Activity recreation.
    val epoch = settingsStore.backup.settingsEpochForUi()
    IconTunerPreferenceState.reconcileEpoch(prefs, epoch)
    val config = decodeIconSettings(settingsStore, prefs)

    @Suppress("UNCHECKED_CAST")
    fun <T> pref(key: String, default: T): T {
        val value = config.value(key) ?: prefs[key] ?: when (default) {
            is Boolean -> settingsStore.getBoolean(key, default)
            is Int -> settingsStore.getInt(key, default)
            is Float -> settingsStore.getFloat(key, default)
            is String -> settingsStore.getString(key, default)
            is Set<*> -> settingsStore.getStringSet(key, default.filterIsInstance<String>().toSet())
            else -> default
        }
        return (if (default is Set<*> && value is List<*>) value.filterIsInstance<String>().toSet() else value) as T
    }

    fun changed(key: String, value: Any) {
        prefs[key] = if (value is Set<*>) {
            ArrayList(value.filterIsInstance<String>())
        } else {
            value
        }
        when (value) {
            is Boolean -> settingsStore.putBoolean(key, value)
            is Int -> settingsStore.putInt(key, value)
            is Float -> settingsStore.putFloat(key, value)
            is String -> settingsStore.putString(key, value)
            is Set<*> -> settingsStore.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
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

    val typeOrder = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER, 0)
    val leftMode = pref(IconTunerContract.KEY_LEFT_MODE, 0)
    val leftSelected = IconTunerContract.leftPreferenceKeys.associateWith { pref(it, false) }
    val explicitSlotModes = IconSlotCatalog.slots.mapNotNull { slot ->
        val key = settingsStore.systemUi.slotKey(slot)
        if (key in config.slots.explicitSlotKeys) slot to pref(key, 0) else null
    }.toMap()
    val slotModesByName = IconSlotPolicy.foldExtraHiddenIntoModes(
        explicitSlotModes, IconSlotPolicy.parseSlotList(config.slots.extBlocked)
    )
    // Hoisted drag state feeds both previews without publishing preferences while dragging.
    val duoComponentSliders = listOf(
        rememberSliderEdit(pref(IconSettingsKeys.KEY_ICON_DUO_RING_SCALE, 100).toFloat()),
        rememberSliderEdit(pref(IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE, 100).toFloat()),
        rememberSliderEdit(pref(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE, 100).toFloat()),
        rememberSliderEdit(pref(IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE, 100).toFloat()),
        rememberSliderEdit(pref(IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE, 100).toFloat()),
        rememberSliderEdit(pref(IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE, 100).toFloat()),
        rememberSliderEdit(pref(IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE, 100).toFloat()),
        rememberSliderEdit(pref(IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE, 100).toFloat()),
    )
    val duoComponentPercents = duoComponentSliders.map { it.value.roundToInt() }
    val duoSizeSlider = rememberSliderEdit(
        pref(IconSettingsKeys.KEY_ICON_DUO_SIZE, DuoLayout.DEFAULT_ICON_SIZE_DP).toFloat()
    )
    val previewModel = buildStatusBarPreview(
        StatusBarPreviewInput(
            position = pref(IconTunerContract.KEY_POSITION, IconSlotPolicyConfig.POSITION_SYSTEM),
            customOrder = pref(IconTunerContract.KEY_POSITION_VALUES, emptySet<String>()),
            reorderHidden = pref(IconTunerContract.KEY_POSITION_REORDER, false),
            slotModes = slotModesByName,
            extraHidden = emptySet(),
            leftSlots = if (leftMode == IconTunerContract.LEFT_MODE_DISABLED) {
                emptySet()
            } else {
                IconTunerContract.leftPreferenceKeys
                    .filter { leftSelected[it] == true }
                    .flatMap { IconTunerContract.slotsForLeftPreference(it) }
                    .toSet()
            },
            rotationLockEnabled = pref(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON, false),
            stackedEnabled = config.signalStyle == SignalStyle.STACKED,
            duoEnabled = config.signalStyle == SignalStyle.DUO,
            duoSizeDp = DuoLayout.snapSizeDp(duoSizeSlider.value).toFloat(),
            hideMobileOnWifi = pref(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, false),
            hideWifiConnected = pref(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false),
            hideNonDefaultSim = pref(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM, false),
            hideSimOne = pref(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, false),
            hideSimTwo = pref(IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO, false),
            useSeparateCellularType = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE, false) ||
                config.signalStyle == SignalStyle.STACKED,
            typeOrder = typeOrder,
            keepCellularTypeOnWifi = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI, false),
            small5GaEnabled = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, false),
            typography = MobileTypeTypography(
                pref(IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY, false),
                pref(IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT, 100f),
                pref(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT, 400)
            ).safe()
        )
    )

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
    val signalStyle = config.signalStyle
    val typography = MobileTypeTypography(
        pref(IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY, false),
        pref(IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT, 100f),
        pref(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT, 400)
    ).safe()
    val onChange: (String, Any) -> Unit = ::changed

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
            StatusBarPreview(
                model = previewModel,
                mirrorAirplane = pref(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false),
                duoSizes = duoSizes(duoComponentPercents)
            )
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
                when (selectedCategory.coerceIn(0, categories.lastIndex)) {
                    0 -> {
                        PositionSection(
                            position = pref(IconTunerContract.KEY_POSITION, IconSlotPolicyConfig.POSITION_SYSTEM),
                            onNavigateToIconOrder = onNavigateToIconOrder
                        )
                        LeftContainerSection(mode = leftMode, leftSelected = leftSelected, onChange = onChange)
                        SlotsSection(
                            slotModes = slotModes,
                            slotModeOf = { slot -> IconSlotPolicy.modeFor(slot, slotModesByName).value },
                            ignoreSysHide = pref(IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE, false),
                            onChange = onChange
                        )
                    }
                    1 -> {
                        SignalStyleSection(
                            style = signalStyle,
                            artworkStyle = pref(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE, 0),
                            weight = pref(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT, 0f),
                            mobileRowStyle = pref(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE, 0),
                            onChange = onChange
                        )
                        MiuixExpandableContent(visible = signalStyle == SignalStyle.DUO) {
                            DuoSignalSection(
                                typography = typography,
                                componentSliders = duoComponentSliders,
                                sizeSlider = duoSizeSlider,
                                mirrorAirplane = pref(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false),
                                expanded = pref(IconSettingsKeys.KEY_ICON_DUO_EXPANDED, 1),
                                topPercent = pref(IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT, false),
                                cellularDots = pref(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_DOTS, false),
                                dualDots = pref(IconSettingsKeys.KEY_ICON_DUO_DUAL_DOTS, false),
                                clickExpand = pref(IconSettingsKeys.KEY_ICON_DUO_CLICK_EXPAND, true),
                                small5GaEnabled = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, false),
                                onChange = onChange
                            )
                        }
                        CellularIndicatorsSection(
                            activity = pref(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY, false),
                            type = pref(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE, false),
                            roamingMode = pref(IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE, 0),
                            voWifi = pref(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOWIFI, false),
                            volte = pref(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE, false),
                            volteNoService = pref(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, false),
                            speechHd = pref(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, false),
                            onChange = onChange
                        )
                        CellularTypeSection(
                            forceSingle = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE, false),
                            stacked = signalStyle == SignalStyle.STACKED,
                            keepTypeOnWifi = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI, false),
                            hideOnWifi = pref(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, false),
                            useCustom = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM, false),
                            customValue = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, ""),
                            typeOrder = typeOrder,
                            hideWhenDisconnected = pref(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, false),
                            small5GaEnabled = pref(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, false),
                            typography = typography,
                            onChange = onChange
                        )
                        SimVisibilitySection(
                            hideNonDefaultSim = pref(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM, false),
                            hideSimOne = pref(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, false),
                            hideSimTwo = pref(IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO, false),
                            hideOnWifi = pref(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, false),
                            onChange = onChange
                        )
                    }
                    2 -> WifiSection(
                        activity = pref(IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY, false),
                        hideConnected = pref(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false),
                        activityRight = pref(IconSettingsKeys.KEY_ICON_WIFI_ACTIVITY_RIGHT, false),
                        standardMode = pref(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE, 0),
                        standardMap = pref(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MAP, "4,5,6,7,8"),
                        paddingEnabled = pref(IconSettingsKeys.KEY_ICON_WIFI_PADDING, false),
                        paddingStart = pref(IconSettingsKeys.KEY_ICON_WIFI_PADDING_START_VAL, 0f),
                        paddingEnd = pref(IconSettingsKeys.KEY_ICON_WIFI_PADDING_END_VAL, 0f),
                        onChange = onChange
                    )
                    3 -> {
                        CarrierSection(
                            hideOne = pref(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_ONE, false),
                            hideTwo = pref(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO, false),
                            hideHd = pref(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD, false),
                            hideLsOne = pref(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE, false),
                            hideLsTwo = pref(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_TWO, false),
                            ccCarrierLeft = pref(SystemUiSettingsKeys.KEY_CC_CARRIER_LEFT, false),
                            ccHideDate = pref(SystemUiSettingsKeys.KEY_CC_HIDE_DATE, false),
                            ccTwoLine = pref(SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE, false),
                            mobileRowStyle = pref(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE, 0),
                            ccNetworkFirstRow = pref(SystemUiSettingsKeys.KEY_CC_NETWORK_FIRST_ROW, false),
                            ccShowNonDataType = pref(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE, false),
                            ccShowBadge = pref(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_BADGE, true),
                            ccBadgeOne = pref(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_ONE, "1"),
                            ccBadgeTwo = pref(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_TWO, "2"),
                            onNavigateToIconRows = onNavigateToIconRows,
                            onChange = onChange
                        )
                        BatteryStyleSection(
                            style = pref(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE, 0),
                            onChange = { changed(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE, it) }
                        )
                        SmallTitle(stringResource(R.string.icon_cc_rotation_lock_title))
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            TunerSwitch(
                                pref(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON, false),
                                stringResource(R.string.icon_cc_rotation_lock),
                                stringResource(R.string.icon_cc_rotation_lock_summary)
                            ) { changed(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON, it) }
                        }
                        StatusBarMiscSection(
                            mirrorAirplane = pref(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false),
                            regionSampling = pref(PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING, 0) != 0,
                            onChange = onChange
                        )
                        NotificationSection(
                            enabled = pref(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_MAX, false),
                            maximum = NotificationIconLimit.clamp(
                                pref(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_ICON_MAX, NotificationIconLimit.DEFAULT)
                            ),
                            onChange = onChange
                        )
                    }
                }
                Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
            }
        }
    }
}

private fun duoSizes(percents: List<Int>) = DuoSizes(
    ring = DuoSizes.ratio(percents[0]),
    wifi = DuoSizes.ratio(percents[1]),
    cellular = DuoSizes.ratio(percents[2]),
    type = DuoSizes.ratio(percents[3]),
    dots = DuoSizes.ratio(percents[4]),
    airplane = DuoSizes.ratio(percents[5]),
    battery = DuoSizes.ratio(percents[6]),
    percent = DuoSizes.ratio(percents[7])
)

/**
 * The signal replacement: one Miuix dropdown, with the options of the selected style expanding
 * underneath it. Duo also draws its bars with the stacked weight, so the weight stays visible for
 * both replacements; the artwork family is stacked-only.
 */
@Composable
private fun SignalStyleSection(
    style: SignalStyle,
    artworkStyle: Int,
    weight: Float,
    mobileRowStyle: Int,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_signal_style))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_signal_style),
                summary = stringResource(
                    when (style) {
                        SignalStyle.NORMAL -> R.string.icon_signal_style_normal_summary
                        SignalStyle.STACKED -> R.string.icon_signal_style_stacked_summary
                        SignalStyle.DUO -> R.string.icon_signal_style_duo_summary
                    }
                ),
                items = listOf(
                    stringResource(R.string.icon_signal_style_normal),
                    stringResource(R.string.icon_signal_style_stacked),
                    stringResource(R.string.icon_signal_style_duo)
                ),
                selectedIndex = style.ordinal,
                onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_SIGNAL_STYLE, it) }
            )
            MiuixExpandableContent(visible = style == SignalStyle.STACKED) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.icon_stacked_signal_artwork),
                    items = listOf(
                        stringResource(R.string.icon_stacked_signal_artwork_hyperos3),
                        stringResource(R.string.icon_stacked_signal_artwork_ios27)
                    ),
                    selectedIndex = SignalSvgStyle.fromStored(artworkStyle).storedValue,
                    onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE, it) }
                )
            }
            MiuixExpandableContent(visible = style != SignalStyle.NORMAL) {
                SliderRow(
                    stringResource(R.string.icon_stacked_signal_weight),
                    SignalWeight.normalize(weight), 0f, 1f,
                    valueLabel = { "${(it * 100).roundToInt()}%" }
                ) { onChange(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT, it) }
            }
            // The SIM order also applies to the control-center carrier rows, so it stays visible.
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_mobile_row_style),
                summary = stringResource(R.string.icon_mobile_row_style_summary),
                items = listOf(
                    stringResource(R.string.icon_mobile_row_style_slot),
                    stringResource(R.string.icon_mobile_row_style_data)
                ),
                selectedIndex = mobileRowStyle.coerceIn(0, 1),
                onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE, it) }
            )
        }
    }
}

/**
 * Left-placement rows: the preference key, its label, and the slots it moves.
 *
 * [previewSlot] is resolved from [IconTunerContract.slotsForLeftPreference] so the row cannot show a
 * different glyph than the hook's actual slot group, and [LeftContainerSectionTest] keeps the list
 * in step with [IconTunerContract.leftPreferenceKeys].
 */
internal data class LeftToggleRow(
    val key: String,
    @StringRes val labelRes: Int,
    /** Representative host slot for the preview. */
    val previewSlot: String
)

internal val LEFT_TOGGLE_ROWS: List<LeftToggleRow> = listOf(
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_ZEN, R.string.icon_left_zen, "zen"),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_VOLUME, R.string.icon_left_volume, "volume"),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_HOTSPOT, R.string.icon_left_hotspot, "hotspot"),
    LeftToggleRow(
        IconSettingsKeys.KEY_ICON_LEFT_ALARM_CLOCK,
        R.string.icon_left_alarm_clock,
        "alarm_clock"
    ),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_LOCATION, R.string.icon_left_location, "location"),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_BLUETOOTH, R.string.icon_left_bluetooth, "bluetooth"),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_NFC, R.string.icon_left_nfc, "nfc"),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_VPN, R.string.icon_left_vpn, "vpn"),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE, R.string.icon_left_airplane, "airplane"),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_HEADSET, R.string.icon_left_headset, "headset"),
    LeftToggleRow(IconSettingsKeys.KEY_ICON_LEFT_ROTATION_LOCK, R.string.icon_slot_rotation_lock, IconSlotPolicy.SLOT_ROTATION_LOCK)
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
                onSelectedIndexChange = { onChange(IconTunerContract.KEY_LEFT_MODE, it) }
            )
            MiuixExpandableContent(visible = mode != IconTunerContract.LEFT_MODE_DISABLED) {
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
                            SlotRowGlyph(
                                iconRes = info?.iconRes ?: IconSlotCatalog.fallbackIconRes(),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                // The row title already names the icon group.
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

/** Accessory indicators drawn next to each SIM's bars. */
@Composable
private fun CellularIndicatorsSection(
    activity: Boolean,
    type: Boolean,
    roamingMode: Int,
    voWifi: Boolean,
    volte: Boolean,
    volteNoService: Boolean,
    speechHd: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_cellular_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                activity,
                stringResource(R.string.icon_hide_data_activity),
                stringResource(R.string.icon_hide_data_activity_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY, it) }
            TunerSwitch(
                type,
                stringResource(R.string.icon_hide_network_type),
                stringResource(R.string.icon_hide_network_type_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE, it) }
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_native_roaming),
                items = listOf(
                    stringResource(R.string.icon_native_roaming_system),
                    stringResource(R.string.icon_native_roaming_hide_regular),
                    stringResource(R.string.icon_native_roaming_hide_small),
                    stringResource(R.string.icon_native_roaming_hide_both)
                ),
                selectedIndex = roamingMode,
                onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE, it) }
            )
            TunerSwitch(
                voWifi,
                stringResource(R.string.icon_hide_vowifi),
                stringResource(R.string.icon_hide_vowifi_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOWIFI, it) }
            TunerSwitch(
                volte,
                stringResource(R.string.icon_hide_volte),
                stringResource(R.string.icon_hide_volte_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE, it) }
            TunerSwitch(
                volteNoService,
                stringResource(R.string.icon_hide_volte_no_service),
                stringResource(R.string.icon_hide_volte_no_service_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, it) }
            TunerSwitch(
                speechHd,
                stringResource(R.string.icon_hide_hd_call),
                stringResource(R.string.icon_hide_hd_call_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, it) }
        }
    }
}

/** Which SIM rows are shown at all. */
@Composable
private fun SimVisibilitySection(
    hideNonDefaultSim: Boolean,
    hideSimOne: Boolean,
    hideSimTwo: Boolean,
    hideOnWifi: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_section_network))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                hideNonDefaultSim,
                stringResource(R.string.icon_hide_non_default_sim_icon),
                stringResource(R.string.icon_hide_non_default_sim_icon_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM, it) }
            TunerSwitch(
                hideSimOne,
                stringResource(R.string.icon_hide_sim_one),
                stringResource(R.string.icon_hide_sim_one_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, it) }
            TunerSwitch(
                hideSimTwo,
                stringResource(R.string.icon_hide_sim_two),
                stringResource(R.string.icon_hide_sim_two_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO, it) }
            TunerSwitch(
                hideOnWifi,
                stringResource(R.string.icon_hide_mobile_on_wifi),
                stringResource(R.string.icon_hide_mobile_on_wifi_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, it) }
        }
    }
}

@Composable
private fun WifiSection(
    activity: Boolean,
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
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY, it) }
            TunerSwitch(
                hideConnected,
                stringResource(R.string.icon_hide_connected_wifi_icon),
                stringResource(R.string.icon_hide_connected_wifi_icon_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE, it) }
            TunerSwitch(
                activityRight,
                stringResource(R.string.icon_wifi_activity_right),
                stringResource(R.string.icon_wifi_activity_right_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_WIFI_ACTIVITY_RIGHT, it) }
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_wifi_standard_mode),
                summary = stringResource(R.string.icon_wifi_standard_mode_summary),
                items = standardModes,
                selectedIndex = standardMode,
                onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE, it) }
            )
            MiuixExpandableContent(visible = standardMode == 3) {
                IconTextPreference(
                    title = stringResource(R.string.icon_wifi_standard_map),
                    value = standardMap,
                    enabled = standardMode == 3,
                    hint = stringResource(R.string.icon_wifi_standard_map_hint)
                ) { onChange(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MAP, it) }
            }
            TunerSwitch(
                paddingEnabled,
                stringResource(R.string.icon_wifi_padding),
                stringResource(R.string.icon_wifi_padding_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_WIFI_PADDING, it) }
            MiuixExpandableContent(visible = paddingEnabled) {
                SliderRow(stringResource(R.string.icon_wifi_padding_start), paddingStart, 0f, 24f) {
                    onChange(IconSettingsKeys.KEY_ICON_WIFI_PADDING_START_VAL, it)
                }
                SliderRow(stringResource(R.string.icon_wifi_padding_end), paddingEnd, 0f, 24f) {
                    onChange(IconSettingsKeys.KEY_ICON_WIFI_PADDING_END_VAL, it)
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
private fun PositionSection(position: Int, onNavigateToIconOrder: () -> Unit) {
    val positions = listOf(
        stringResource(R.string.icon_position_system),
        stringResource(R.string.icon_position_wifi_before_mobile),
        stringResource(R.string.icon_position_custom)
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

/** The status-bar options outside every icon group: airplane mirroring and region sampling. */
@Composable
private fun StatusBarMiscSection(mirrorAirplane: Boolean, regionSampling: Boolean, onChange: (String, Any) -> Unit) {
    SmallTitle(stringResource(R.string.icon_section_status_bar))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                mirrorAirplane,
                stringResource(R.string.icon_mirror_airplane),
                stringResource(R.string.icon_mirror_airplane_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, it) }
            TunerSwitch(
                regionSampling,
                stringResource(R.string.icon_region_sampling),
                stringResource(R.string.icon_region_sampling_summary)
            ) { onChange(PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING, if (it) 1 else 0) }
        }
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
            ) { onChange(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_MAX, it) }
            MiuixExpandableContent(visible = enabled) {
                NotificationMaxIconsRow(maximum) {
                    onChange(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_ICON_MAX, it)
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
    val slider = rememberSliderEdit(current.toFloat())
    val sliderValue = slider.value
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
                onValueChange = slider::update,
                onValueChangeFinished = { slider.finish { it.roundToInt().toFloat() }?.let { onValueChange(it.toInt()) } },
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
    typography: MobileTypeTypography,
    forceSingle: Boolean,
    stacked: Boolean,
    keepTypeOnWifi: Boolean,
    hideOnWifi: Boolean,
    useCustom: Boolean,
    customValue: String,
    typeOrder: Int,
    hideWhenDisconnected: Boolean,
    small5GaEnabled: Boolean,
    onChange: (String, Any) -> Unit
) {
    // Stacked bars always pair with the native standalone label, so it is forced on there.
    val separate = forceSingle || stacked
    SmallTitle(stringResource(R.string.icon_cellular_type_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                separate,
                stringResource(R.string.icon_cellular_type_single),
                stringResource(
                    if (stacked) R.string.icon_cellular_type_single_stacked_summary
                    else R.string.icon_cellular_type_single_summary
                ),
                enabled = !stacked
            ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE, it) }
            TunerSwitch(
                keepTypeOnWifi,
                stringResource(R.string.icon_stacked_type_keep_on_wifi),
                stringResource(R.string.icon_stacked_type_keep_on_wifi_summary),
                enabled = separate && !hideOnWifi
            ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI, it) }
            TunerSwitch(
                useCustom,
                stringResource(R.string.icon_cellular_type_custom),
                stringResource(R.string.icon_cellular_type_custom_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM, it) }
            TunerSwitch(
                small5GaEnabled,
                stringResource(R.string.icon_cellular_type_small_5ga),
                stringResource(R.string.icon_cellular_type_small_5ga_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, it) }
            TunerSwitch(
                typography.enabled,
                stringResource(R.string.icon_type_typography),
                stringResource(R.string.icon_type_typography_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY, it) }
            MiuixExpandableContent(visible = typography.enabled) {
                SliderRow(stringResource(R.string.icon_type_size), typography.sizePercent, 50f, 150f,
                    valueLabel = { "${it.roundToInt()}%" }
                ) { onChange(IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT, it) }
                SliderRow(stringResource(R.string.icon_type_weight), typography.weight.toFloat(), 100f, 900f,
                    valueLabel = { it.roundToInt().toString() }
                ) { onChange(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT, it.roundToInt()) }
            }
            MiuixExpandableContent(visible = useCustom) {
                IconTextPreference(
                    title = stringResource(R.string.icon_cellular_type_custom_val),
                    value = customValue,
                    enabled = useCustom
                ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, it) }
            }
            TunerSwitch(
                hideWhenDisconnected,
                stringResource(R.string.icon_stacked_type_hide_disconnect),
                stringResource(R.string.icon_stacked_type_hide_disconnect_summary),
                enabled = separate
            ) { onChange(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, it) }
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_cellular_type_swap),
                summary = stringResource(R.string.icon_cellular_type_swap_summary),
                items = listOf(
                    stringResource(R.string.icon_type_order_default),
                    stringResource(R.string.icon_type_order_swap),
                    stringResource(R.string.icon_type_order_right),
                    stringResource(R.string.icon_type_order_left)
                ),
                selectedIndex = if (stacked) 0 else typeOrder,
                enabled = !stacked,
                onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER, it) }
            )
        }
    }
}

@Composable
private fun BatteryStyleSection(style: Int, onChange: (Int) -> Unit) {
    SmallTitle(stringResource(R.string.icon_battery_style_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_battery_style_percent),
            items = listOf(
                stringResource(R.string.icon_battery_style_system),
                stringResource(R.string.icon_battery_style_trailing),
                stringResource(R.string.icon_battery_style_leading)
            ),
            selectedIndex = style,
            onSelectedIndexChange = onChange
        )
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
    mobileRowStyle: Int,
    ccNetworkFirstRow: Boolean,
    ccShowNonDataType: Boolean,
    ccShowBadge: Boolean,
    ccBadgeOne: String,
    ccBadgeTwo: String,
    onNavigateToIconRows: () -> Unit,
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
            ) { onChange(SystemUiSettingsKeys.KEY_CC_CARRIER_LEFT, it) }
            // 开关 2: hide the control-center date; it is also the enabling switch of 开关 3/4.
            TunerSwitch(
                ccHideDate,
                stringResource(R.string.icon_cc_hide_date),
                stringResource(R.string.icon_cc_hide_date_summary)
            ) { onChange(SystemUiSettingsKeys.KEY_CC_HIDE_DATE, it) }
            TunerSwitch(
                ccTwoLine,
                stringResource(R.string.icon_cc_carrier_two_line),
                stringResource(R.string.icon_cc_carrier_two_line_summary),
                enabled = ccHideDate
            ) { onChange(SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE, it) }
            MiuixExpandableContent(visible = mobileRowStyle == 0) {
                TunerSwitch(
                    ccNetworkFirstRow,
                    stringResource(R.string.icon_cc_network_first_row),
                    stringResource(R.string.icon_cc_network_first_row_summary),
                    enabled = ccHideDate && ccTwoLine
                ) { onChange(SystemUiSettingsKeys.KEY_CC_NETWORK_FIRST_ROW, it) }
            }
            ArrowPreference(
                title = stringResource(R.string.icon_cc_rows_title),
                summary = stringResource(R.string.icon_cc_rows_entry_summary),
                onClick = onNavigateToIconRows
            )
            TunerSwitch(
                ccShowBadge,
                stringResource(R.string.icon_cc_carrier_show_badge),
                stringResource(R.string.icon_cc_carrier_show_badge_summary),
                enabled = ccHideDate && ccTwoLine
            ) { onChange(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_BADGE, it) }
            MiuixExpandableContent(visible = ccHideDate && ccTwoLine && ccShowBadge) {
                IconTextPreference(
                    title = stringResource(R.string.icon_cc_carrier_badge_one),
                    value = ccBadgeOne,
                    enabled = ccHideDate && ccTwoLine && ccShowBadge
                ) { onChange(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_ONE, it) }
                IconTextPreference(
                    title = stringResource(R.string.icon_cc_carrier_badge_two),
                    value = ccBadgeTwo,
                    enabled = ccHideDate && ccTwoLine && ccShowBadge
                ) { onChange(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_TWO, it) }
            }
            TunerSwitch(
                ccShowNonDataType,
                stringResource(R.string.icon_cc_carrier_show_non_data_type),
                stringResource(R.string.icon_cc_carrier_show_non_data_type_summary),
                enabled = ccHideDate && ccTwoLine
            ) { onChange(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE, it) }
            TunerSwitch(
                hideOne,
                stringResource(R.string.icon_hide_carrier_one),
                stringResource(if (ccHideDate && ccTwoLine) R.string.icon_cc_carrier_name_priority
                    else R.string.icon_hide_carrier_one_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_ONE, it) }
            TunerSwitch(
                hideTwo,
                stringResource(R.string.icon_hide_carrier_two),
                stringResource(if (ccHideDate && ccTwoLine) R.string.icon_cc_carrier_name_priority
                    else R.string.icon_hide_carrier_two_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO, it) }
            TunerSwitch(
                hideHd,
                stringResource(R.string.icon_hide_carrier_hd),
                stringResource(R.string.icon_hide_carrier_hd_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD, it) }
            TunerSwitch(
                hideLsOne,
                stringResource(R.string.icon_hide_ls_carrier_one),
                stringResource(R.string.icon_hide_ls_carrier_one_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE, it) }
            TunerSwitch(
                hideLsTwo,
                stringResource(R.string.icon_hide_ls_carrier_two),
                stringResource(R.string.icon_hide_ls_carrier_two_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_TWO, it) }
        }
    }
}

@Composable
private fun SlotsSection(
    slotModes: List<String>,
    slotModeOf: (String) -> Int,
    ignoreSysHide: Boolean,
    onChange: (String, Any) -> Unit
) {
    val settingsStore = LocalAppSettingsStore.current
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
                        onChange(settingsStore.systemUi.slotKey(slot), index)
                    },
                    startAction = {
                        SlotRowGlyph(
                            iconRes = info?.iconRes ?: IconSlotCatalog.fallbackIconRes(),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            // The row title already names the slot, so the preview stays decorative.
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                )
            }
            TunerSwitch(
                ignoreSysHide,
                stringResource(R.string.icon_ignore_system_hiding),
                stringResource(R.string.icon_ignore_system_hiding_summary)
            ) { onChange(IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE, it) }
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, rangeStart: Float, rangeEnd: Float, normalize: (Float) -> Float = { it }, valueLabel: ((Float) -> String)? = null, editState: SliderEditState? = null, onValue: (Float) -> Unit) {
    val slider = editState ?: rememberSliderEdit(value)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(valueLabel?.let { "$title · ${it(slider.value)}" } ?: title)
        Slider(
            value = slider.value.coerceIn(rangeStart, rangeEnd),
            onValueChange = slider::update,
            onValueChangeFinished = { slider.finish(normalize)?.let(onValue) },
            valueRange = rangeStart..rangeEnd
        )
    }
}

@Composable
private fun IntSliderRow(title: String, value: Int, rangeStart: Int, rangeEnd: Int, editState: SliderEditState? = null, onValue: (Int) -> Unit) {
    SliderRow(title, value.toFloat(), rangeStart.toFloat(), rangeEnd.toFloat(),
        normalize = { it.roundToInt().coerceIn(rangeStart, rangeEnd).toFloat() },
        valueLabel = { it.roundToInt().toString() }, editState = editState) {
        onValue(it.roundToInt().coerceIn(rangeStart, rangeEnd))
    }
}

@Composable
private fun DuoSignalSection(
    typography: MobileTypeTypography,
    componentSliders: List<SliderEditState>,
    sizeSlider: SliderEditState,
    mirrorAirplane: Boolean,
    expanded: Int,
    topPercent: Boolean,
    dualDots: Boolean,
    cellularDots: Boolean,
    clickExpand: Boolean,
    small5GaEnabled: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_duo_title))
    val componentPercents = componentSliders.map { it.value.roundToInt() }
    val sizes = duoSizes(componentPercents)
    DuoSignalPreview(small5GaEnabled, sizes, mirrorAirplane, topPercent, dualDots, cellularDots, typography)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_duo_expanded),
                items = listOf(stringResource(R.string.icon_duo_keep), stringResource(R.string.icon_duo_native)),
                selectedIndex = expanded.coerceIn(0, 1),
                onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_DUO_EXPANDED, it) }
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_duo_cellular_style),
                items = listOf(stringResource(R.string.icon_duo_cellular_bars), stringResource(R.string.icon_duo_cellular_dots)),
                selectedIndex = if (cellularDots) 1 else 0,
                onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_DOTS, it == 1) }
            )
            TunerSwitch(topPercent, stringResource(R.string.icon_duo_top_percent),
                stringResource(R.string.icon_duo_top_percent_summary)) { onChange(IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT, it) }
            TunerSwitch(dualDots, stringResource(R.string.icon_duo_dual_dots),
                stringResource(R.string.icon_duo_dual_dots_summary)) { onChange(IconSettingsKeys.KEY_ICON_DUO_DUAL_DOTS, it) }
            TunerSwitch(clickExpand, stringResource(R.string.icon_duo_click_expand),
                stringResource(R.string.icon_duo_click_expand_summary)) { onChange(IconSettingsKeys.KEY_ICON_DUO_CLICK_EXPAND, it) }
            DuoSizeRow(sizeSlider) { onChange(IconSettingsKeys.KEY_ICON_DUO_SIZE, it) }
            var advanced by rememberSaveable { mutableStateOf(false) }
            TunerSwitch(advanced, stringResource(R.string.icon_duo_advanced)) { advanced = it }
            MiuixExpandableContent(visible = advanced) {
                val components = listOf(
                    IconSettingsKeys.KEY_ICON_DUO_RING_SCALE to R.string.icon_duo_ring_scale,
                    IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE to R.string.icon_duo_wifi_scale,
                    IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE to R.string.icon_duo_cellular_scale,
                    IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE to R.string.icon_duo_type_scale,
                    IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE to R.string.icon_duo_dots_scale,
                    IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE to R.string.icon_duo_airplane_scale,
                    IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE to R.string.icon_duo_battery_scale,
                    IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE to R.string.icon_duo_percent_scale
                )
                components.forEachIndexed { index, (key, title) ->
                    IntSliderRow(stringResource(title),
                        componentPercents[index], 50, 150, editState = componentSliders[index]) {
                        onChange(key, it)
                    }
                }
            }
        }
    }
}

/**
 * Duo glyph size in dp, built like the interface-scale row in `AppearancePage`: an arrow row that
 * shows the current size in its end actions and expands into the slider underneath, and whose tap
 * also opens [IntValueDialog] for an exact number, because a slider cannot reach an arbitrary dp on
 * a narrow screen.
 *
 * The key points put a magnetic detent on the 24dp default, so the untouched size is reachable by
 * feel; the released value is normalised through [DuoLayout.snapSizeDp], which also rounds the
 * continuous track to whole dp.
 */
@Composable
private fun DuoSizeRow(slider: SliderEditState, onChange: (Int) -> Unit) {
    val sizeDp = DuoLayout.snapSizeDp(slider.value)
    val sliderValue = slider.value
    var expanded by remember { mutableStateOf(false) }
    ArrowPreference(
        title = stringResource(R.string.icon_duo_size),
        summary = stringResource(R.string.icon_duo_size_summary),
        endActions = {
            Text(
                text = stringResource(R.string.icon_duo_size_value, sliderValue.roundToInt()),
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        },
        onClick = { expanded = !expanded },
        holdDownState = expanded,
        bottomAction = {
            Slider(
                value = sliderValue.coerceIn(
                    DuoLayout.MIN_ICON_SIZE_DP.toFloat(),
                    DuoLayout.MAX_ICON_SIZE_DP.toFloat()
                ),
                onValueChange = slider::update,
                onValueChangeFinished = {
                    slider.finish { DuoLayout.snapSizeDp(it).toFloat() }?.let { onChange(it.toInt()) }
                },
                valueRange = DuoLayout.MIN_ICON_SIZE_DP.toFloat()..DuoLayout.MAX_ICON_SIZE_DP.toFloat(),
                showKeyPoints = true,
                keyPoints = DuoLayout.SLIDER_KEY_POINTS_DP,
                magnetThreshold = DuoLayout.SLIDER_MAGNET_THRESHOLD,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }
    )
    IntValueDialog(
        show = expanded,
        title = stringResource(R.string.icon_duo_size),
        summary = stringResource(R.string.icon_duo_size_summary),
        suffix = stringResource(R.string.icon_duo_size_unit),
        range = DuoLayout.MIN_ICON_SIZE_DP..DuoLayout.MAX_ICON_SIZE_DP,
        currentValue = { sizeDp },
        // A blank field keeps the current size instead of jumping to the minimum.
        emptyValue = sizeDp,
        onValueConfirmed = onChange,
        onDismissRequest = { expanded = false }
    )
}
