package com.takekazex.hypertweak.ui.page.systemui.icon

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.AirplaneLabelModes
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.settings.contract.NotificationIconLimit
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

/** Slots and left-placement keys whose controls live in a dedicated card, not the generic lists. */
private val OWN_CARD_SLOTS = setOf(IconSlotPolicy.SLOT_ROTATION_LOCK, IconSlotPolicy.SLOT_AIRPLANE)
private val OWN_CARD_LEFT_KEYS = setOf(IconSettingsKeys.KEY_ICON_LEFT_ROTATION_LOCK, IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE)

/**
 * 飞行模式: the native slot's scope, mirroring and left placement, plus how each header's carrier
 * area shows the host's airplane-mode label. One dropdown per surface avoids meaningless
 * combinations of a hide switch and an icon position.
 */
@Composable
internal fun AirplaneSection(
    slotMode: Int,
    slotModes: List<String>,
    mirror: Boolean,
    leftPlacementEnabled: Boolean,
    leftSelected: Boolean,
    labels: AirplaneLabelModes,
    notificationCarrierHidden: Boolean,
    onChange: (String, Any) -> Unit
) {
    val settingsStore = LocalAppSettingsStore.current
    val labelModes = listOf(
        stringResource(R.string.icon_airplane_label_system),
        stringResource(R.string.icon_airplane_label_hide),
        stringResource(R.string.icon_airplane_label_icon),
        stringResource(R.string.icon_airplane_label_icon_start),
        stringResource(R.string.icon_airplane_label_icon_end)
    )
    AppSection(title = stringResource(R.string.icon_airplane_title)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_rotation_lock_visibility),
            items = slotModes,
            selectedIndex = slotMode.coerceIn(0, slotModes.lastIndex),
            onSelectedIndexChange = {
                onChange(settingsStore.systemUi.slotKey(IconSlotPolicy.SLOT_AIRPLANE), it)
            }
        )
        TunerSwitch(
            mirror,
            stringResource(R.string.icon_mirror_airplane),
            stringResource(R.string.icon_mirror_airplane_summary)
        ) { onChange(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, it) }
        MiuixExpandableContent(visible = leftPlacementEnabled) {
            TunerSwitch(
                leftSelected,
                stringResource(R.string.icon_left_container_title)
            ) { onChange(IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE, it) }
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_airplane_label_control_center),
            summary = stringResource(R.string.icon_airplane_label_summary),
            items = labelModes,
            selectedIndex = labels.controlCenter.storedValue,
            onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_CONTROL_CENTER, it) }
        )
        // The notification header's hide-carrier switch removes the whole carrier area; the
        // saved value is kept and applies again once that switch is off.
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_airplane_label_notification),
            summary = stringResource(
                if (notificationCarrierHidden) R.string.icon_airplane_label_notification_hidden
                else R.string.icon_airplane_label_summary
            ),
            items = labelModes,
            selectedIndex = labels.notification.storedValue,
            enabled = !notificationCarrierHidden,
            onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_NOTIFICATION, it) }
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_airplane_label_keyguard),
            summary = stringResource(R.string.icon_airplane_label_summary),
            items = labelModes,
            selectedIndex = labels.keyguard.storedValue,
            onSelectedIndexChange = { onChange(IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_KEYGUARD, it) }
        )
    }
}

@Composable
internal fun RotationLockSection(
    enabled: Boolean,
    leftPlacementEnabled: Boolean,
    leftSelected: Boolean,
    slotMode: Int,
    slotModes: List<String>,
    onChange: (String, Any) -> Unit
) {
    val settingsStore = LocalAppSettingsStore.current
    AppSection(title = stringResource(R.string.icon_cc_rotation_lock_title)) {
        TunerSwitch(
            enabled,
            stringResource(R.string.icon_cc_rotation_lock),
            stringResource(R.string.icon_cc_rotation_lock_summary)
        ) { onChange(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON, it) }
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_rotation_lock_visibility),
            items = slotModes,
            selectedIndex = slotMode.coerceIn(0, slotModes.lastIndex),
            onSelectedIndexChange = {
                onChange(settingsStore.systemUi.slotKey(IconSlotPolicy.SLOT_ROTATION_LOCK), it)
            }
        )
        MiuixExpandableContent(visible = leftPlacementEnabled) {
            TunerSwitch(
                leftSelected,
                stringResource(R.string.icon_left_container_title)
            ) { onChange(IconSettingsKeys.KEY_ICON_LEFT_ROTATION_LOCK, it) }
        }
    }
}

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
internal fun LeftContainerSection(
    mode: Int,
    keyguardCarrier: Int,
    leftSelected: Map<String, Boolean>,
    onChange: (String, Any) -> Unit
) {
    val modes = listOf(
        stringResource(R.string.icon_left_mode_disabled),
        stringResource(R.string.icon_left_mode_home),
        stringResource(R.string.icon_left_mode_both)
    )
    AppSection(title = stringResource(R.string.icon_left_container_title)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.icon_left_container_mode),
            summary = stringResource(R.string.icon_left_container_mode_summary),
            items = modes,
            selectedIndex = mode.coerceIn(0, modes.lastIndex),
            onSelectedIndexChange = { onChange(IconTunerContract.KEY_LEFT_MODE, it) }
        )
        // The lockscreen carrier and the left icons share one start-edge frame.
        MiuixExpandableContent(visible = mode == IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN) {
            val carrierModes = listOf(
                stringResource(R.string.icon_left_keyguard_carrier_replace),
                stringResource(R.string.icon_left_keyguard_carrier_intro)
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_left_keyguard_carrier),
                summary = stringResource(R.string.icon_left_keyguard_carrier_summary),
                items = carrierModes,
                selectedIndex = keyguardCarrier.coerceIn(0, carrierModes.lastIndex),
                onSelectedIndexChange = { onChange(IconTunerContract.KEY_LEFT_KEYGUARD_CARRIER, it) }
            )
        }
        MiuixExpandableContent(visible = mode != IconTunerContract.LEFT_MODE_DISABLED) {
            Text(
                text = stringResource(R.string.icon_left_hint),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            // Rotation lock and airplane mode own their placement controls in their own cards.
            LEFT_TOGGLE_ROWS.filterNot { it.key in OWN_CARD_LEFT_KEYS }.forEach { row ->
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

/**
 * Entry row for the order editor. The controls themselves live on [IconOrderPage], which renders
 * the same slot catalog as [SlotsSection]; the two comma-separated host-slot fields this row used
 * to expose were removed because they required the internal wire names to be known by hand.
 *
 * The current mode is read live instead of through the page's local preference cache: the sub-page
 * writes the same key, and a remembered copy would show the value from before the visit.
 */
@Composable
internal fun PositionSection(position: Int, onNavigateToIconOrder: () -> Unit) {
    val positions = listOf(
        stringResource(R.string.icon_position_system),
        stringResource(R.string.icon_position_wifi_before_mobile),
        stringResource(R.string.icon_position_custom)
    )
    AppSection(title = stringResource(R.string.icon_position_title)) {
        ArrowPreference(
            title = stringResource(R.string.icon_position_title),
            summary = positions[position.coerceIn(0, positions.lastIndex)],
            onClick = onNavigateToIconOrder
        )
    }
}

/** The status-bar options outside every icon group. */
@Composable
internal fun StatusBarMiscSection(regionSampling: Boolean, onChange: (String, Any) -> Unit) {
    AppSection(title = stringResource(R.string.icon_section_status_bar)) {
        TunerSwitch(
            regionSampling,
            stringResource(R.string.icon_region_sampling),
            stringResource(R.string.icon_region_sampling_summary)
        ) { onChange(PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING, if (it) 1 else 0) }
    }
}

@Composable
internal fun NotificationSection(
    enabled: Boolean,
    maximum: Int,
    onChange: (String, Any) -> Unit
) {
    AppSection(title = stringResource(R.string.icon_notification_title)) {
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
internal fun BatteryStyleSection(style: Int, onChange: (Int) -> Unit) {
    AppSection(title = stringResource(R.string.icon_battery_style_title)) {
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
internal fun SlotsSection(
    slotModes: List<String>,
    slotModeOf: (String) -> Int,
    ignoreSysHide: Boolean,
    onChange: (String, Any) -> Unit
) {
    val settingsStore = LocalAppSettingsStore.current
    AppSection(title = stringResource(R.string.icon_slots_title)) {
        IconSlotCatalog.slots.filterNot { it in OWN_CARD_SLOTS }.forEach { slot ->
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
