package com.takekazex.hypertweak.ui.page.systemui.icon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoSizes
import com.takekazex.hypertweak.settings.contract.CarrierRowVisibility
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.MobileTypeTypography
import com.takekazex.hypertweak.settings.contract.SignalStyle
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.settings.contract.SignalWeight
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.components.slider.SliderEditState
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun duoSizes(percents: List<Int>) = DuoSizes(
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
 * both replacements; the artwork family also applies to Duo's expanded stacked destination.
 */
@Composable
internal fun SignalStyleSection(
    style: SignalStyle,
    stackedArtwork: Boolean,
    artworkStyle: Int,
    weight: Float,
    mobileRowStyle: Int,
    onChange: (String, Any) -> Unit
) {
    AppSection(title = stringResource(R.string.icon_signal_style)) {
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
        MiuixExpandableContent(visible = stackedArtwork) {
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

/** Accessory indicators drawn next to each SIM's bars. */
@Composable
internal fun CellularIndicatorsSection(
    activity: Boolean,
    type: Boolean,
    roamingMode: Int,
    voWifi: Boolean,
    volte: Boolean,
    volteNoService: Boolean,
    speechHd: Boolean,
    onChange: (String, Any) -> Unit
) {
    AppSection(title = stringResource(R.string.icon_cellular_title)) {
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

/** Which SIM rows are shown at all. */
@Composable
internal fun SimVisibilitySection(
    nonDefaultMode: Int,
    simOneMode: Int,
    simTwoMode: Int,
    hideLsOne: Boolean,
    hideLsTwo: Boolean,
    hideOnWifi: Boolean,
    onChange: (String, Any) -> Unit
) {
    val modes = listOf(
        stringResource(R.string.icon_sim_visibility_default),
        stringResource(R.string.icon_sim_visibility_status_bar),
        stringResource(R.string.icon_sim_visibility_all)
    )
    AppSection(title = stringResource(R.string.icon_sim_visibility_title)) {
        listOf(
            Triple(IconSettingsKeys.KEY_ICON_SIM_ONE_VISIBILITY, R.string.icon_sim_visibility_one, simOneMode),
            Triple(IconSettingsKeys.KEY_ICON_SIM_TWO_VISIBILITY, R.string.icon_sim_visibility_two, simTwoMode),
            Triple(IconSettingsKeys.KEY_ICON_NON_DEFAULT_SIM_VISIBILITY, R.string.icon_sim_visibility_non_default, nonDefaultMode)
        ).forEach { (key, title, mode) ->
            OverlayDropdownPreference(
                title = stringResource(title),
                summary = stringResource(R.string.icon_sim_visibility_summary),
                items = modes,
                selectedIndex = mode,
                onSelectedIndexChange = { onChange(key, it) }
            )
        }
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
    AppSection(title = stringResource(R.string.icon_section_network)) {
        TunerSwitch(
            hideOnWifi,
            stringResource(R.string.icon_hide_mobile_on_wifi),
            stringResource(R.string.icon_hide_mobile_on_wifi_summary)
        ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, it) }
    }
}

@Composable
internal fun WifiSection(
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
    AppSection(title = stringResource(R.string.icon_wifi_title)) {
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

@Composable
internal fun CellularTypeSection(
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
    AppSection(title = stringResource(R.string.icon_cellular_type_title)) {
        TunerSwitch(
            separate,
            stringResource(R.string.icon_cellular_type_single),
            stringResource(
                if (stacked) R.string.icon_cellular_type_single_stacked_summary
                else R.string.icon_cellular_type_single_summary
            ),
            enabled = !stacked
        ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE, it) }
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_cellular_type_swap),
            summary = stringResource(
                if (stacked) R.string.icon_cellular_type_swap_stacked_summary
                else R.string.icon_cellular_type_swap_summary
            ),
            items = listOf(
                stringResource(R.string.icon_type_order_default),
                stringResource(R.string.icon_type_order_swap),
                stringResource(R.string.icon_type_order_right),
                stringResource(R.string.icon_type_order_left)
            ),
            selectedIndex = typeOrder,
            onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER, it) }
        )
        TunerSwitch(
            keepTypeOnWifi,
            stringResource(R.string.icon_stacked_type_keep_on_wifi),
            stringResource(R.string.icon_stacked_type_keep_on_wifi_summary),
            enabled = separate && !hideOnWifi
        ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI, it) }
        TunerSwitch(
            hideWhenDisconnected,
            stringResource(R.string.icon_stacked_type_hide_disconnect),
            stringResource(R.string.icon_stacked_type_hide_disconnect_summary),
            enabled = separate
        ) { onChange(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, it) }
        TunerSwitch(
            useCustom,
            stringResource(R.string.icon_cellular_type_custom),
            stringResource(R.string.icon_cellular_type_custom_summary)
        ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM, it) }
        MiuixExpandableContent(visible = useCustom) {
            IconTextPreference(
                title = stringResource(R.string.icon_cellular_type_custom_val),
                value = customValue,
                enabled = useCustom
            ) { onChange(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, it) }
        }
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
    }
}

@Composable
internal fun CarrierSection(
    carrierHiding: Int,
    hideHd: Boolean,
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
    val nativeCarrierOptionsEnabled = CarrierRowVisibility.nativeControlsEnabled(ccHideDate, ccTwoLine)
    AppSection(title = stringResource(R.string.icon_carrier_title)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_cc_hide_carrier_name),
            summary = stringResource(if (nativeCarrierOptionsEnabled) R.string.icon_cc_hide_carrier_name_summary
                else R.string.icon_cc_hide_carrier_two_line_disabled),
            items = listOf(
                stringResource(R.string.icon_cc_carrier_name_show),
                stringResource(R.string.icon_cc_carrier_name_hide_one),
                stringResource(R.string.icon_cc_carrier_name_hide_two),
                stringResource(R.string.icon_cc_carrier_name_hide_both)
            ),
            selectedIndex = carrierHiding,
            enabled = nativeCarrierOptionsEnabled,
            onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_CC_CARRIER_HIDING, it) }
        )
        // 开关 1: control-center carrier label on the leading edge.
        TunerSwitch(
            ccCarrierLeft,
            stringResource(R.string.icon_cc_carrier_left),
            stringResource(if (nativeCarrierOptionsEnabled) R.string.icon_cc_carrier_left_summary
                else R.string.icon_cc_carrier_left_two_line_summary),
            enabled = nativeCarrierOptionsEnabled
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
            hideHd,
            stringResource(R.string.icon_hide_carrier_hd),
            stringResource(R.string.icon_hide_carrier_hd_summary)
        ) { onChange(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD, it) }
    }
}

@Composable
internal fun DuoSignalSection(
    typography: MobileTypeTypography,
    componentSliders: List<SliderEditState>,
    sizeSlider: SliderEditState,
    mirrorAirplane: Boolean,
    expanded: Int,
    expandedSignalStyle: Int,
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
    AppSection {
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_duo_expanded),
            items = listOf(stringResource(R.string.icon_duo_keep), stringResource(R.string.icon_duo_native)),
            selectedIndex = expanded.coerceIn(0, 1),
            onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_DUO_EXPANDED, it) }
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_duo_expanded_signal_style),
            summary = stringResource(R.string.icon_duo_expanded_signal_style_summary),
            items = listOf(
                stringResource(R.string.icon_signal_style_normal),
                stringResource(R.string.icon_signal_style_stacked)
            ),
            selectedIndex = expandedSignalStyle.coerceIn(0, 1),
            onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_DUO_EXPANDED_SIGNAL_STYLE, it) }
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
