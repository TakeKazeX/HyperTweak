package com.takekazex.hypertweak.ui.page.systemui.icon

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.settings.contract.MobileTypeTypography
import com.takekazex.hypertweak.settings.contract.NotificationIconLimit
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SignalStyle
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppOverlayDialog
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppTabRow
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.slider.SliderEditState
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

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
internal fun TunerSwitch(
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
internal fun TunerSwitch(
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
internal fun IconTextPreference(
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
            hideNonDefaultSim = config.network.hideNonDefaultSim,
            hideSimOne = config.network.hideSimOne,
            hideSimTwo = config.network.hideSimTwo,
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
    val signalStyle = config.signalStyle
    val typography = MobileTypeTypography(
        pref(IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY, false),
        pref(IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT, 100f),
        pref(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT, 400)
    ).safe()
    val onChange: (String, Any) -> Unit = ::changed

    AppScrollPage(
        title = stringResource(R.string.icon_page_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.icon_back),
        topBarBottom = {
            Column(Modifier.fillMaxWidth()) {
                StatusBarPreview(
                    model = previewModel,
                    mirrorAirplane = pref(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false),
                    duoSizes = duoSizes(duoComponentPercents)
                )
                AppTabRow(tabs = categories, selectedIndex = selectedCategory, onSelect = { selectedCategory = it })
            }
        },
    ) {

        when (selectedCategory.coerceIn(0, categories.lastIndex)) {
            0 -> {
                PositionSection(
                    position = pref(IconTunerContract.KEY_POSITION, IconSlotPolicyConfig.POSITION_SYSTEM),
                    onNavigateToIconOrder = onNavigateToIconOrder
                )
                LeftContainerSection(
                    mode = leftMode,
                    keyguardCarrier = pref(
                        IconTunerContract.KEY_LEFT_KEYGUARD_CARRIER,
                        IconTunerContract.LEFT_KEYGUARD_CARRIER_REPLACE
                    ),
                    leftSelected = leftSelected,
                    onChange = onChange
                )
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
                    stackedArtwork = config.hasStackedSignal,
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
                        expandedSignalStyle = pref(IconSettingsKeys.KEY_ICON_DUO_EXPANDED_SIGNAL_STYLE, 0),
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
                    stacked = config.hasStackedSignal,
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
                    nonDefaultMode = config.network.nonDefaultSimVisibility,
                    simOneMode = config.network.simOneVisibility,
                    simTwoMode = config.network.simTwoVisibility,
                    hideLsOne = pref(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE, false),
                    hideLsTwo = pref(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_TWO, false),
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
                    carrierHiding = pref(IconSettingsKeys.KEY_ICON_CC_CARRIER_HIDING, 0),
                    hideHd = pref(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD, false),
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
                RotationLockSection(
                    enabled = pref(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON, false),
                    leftPlacementEnabled = leftMode != IconTunerContract.LEFT_MODE_DISABLED,
                    leftSelected = leftSelected[IconSettingsKeys.KEY_ICON_LEFT_ROTATION_LOCK] == true,
                    slotMode = IconSlotPolicy.modeFor(IconSlotPolicy.SLOT_ROTATION_LOCK, slotModesByName).value,
                    slotModes = slotModes,
                    onChange = onChange
                )
                AirplaneSection(
                    slotMode = IconSlotPolicy.modeFor(IconSlotPolicy.SLOT_AIRPLANE, slotModesByName).value,
                    slotModes = slotModes,
                    mirror = pref(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false),
                    leftPlacementEnabled = leftMode != IconTunerContract.LEFT_MODE_DISABLED,
                    leftSelected = leftSelected[IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE] == true,
                    labels = config.misc.airplaneLabelModes,
                    notificationCarrierHidden = pref(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_CARRIER, false),
                    onChange = onChange
                )
                StatusBarMiscSection(
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
    }
}

@Composable
internal fun SliderRow(title: String, value: Float, rangeStart: Float, rangeEnd: Float, normalize: (Float) -> Float = { it }, valueLabel: ((Float) -> String)? = null, editState: SliderEditState? = null, onValue: (Float) -> Unit) {
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
internal fun IntSliderRow(title: String, value: Int, rangeStart: Int, rangeEnd: Int, editState: SliderEditState? = null, onValue: (Int) -> Unit) {
    SliderRow(title, value.toFloat(), rangeStart.toFloat(), rangeEnd.toFloat(),
        normalize = { it.roundToInt().coerceIn(rangeStart, rangeEnd).toFloat() },
        valueLabel = { it.roundToInt().toString() }, editState = editState) {
        onValue(it.roundToInt().coerceIn(rangeStart, rangeEnd))
    }
}
