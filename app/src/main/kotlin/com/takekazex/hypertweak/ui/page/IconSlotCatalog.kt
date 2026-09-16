package com.takekazex.hypertweak.ui.page

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Duo
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LooksOne
import androidx.compose.material.icons.rounded.LooksTwo
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SignalCellularAlt2Bar
import androidx.compose.material.icons.rounded.SimCardAlert
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicy

/**
 * Display metadata for one icon-tuner slot row.
 *
 * The host slot names are stable wire values (`Preferences.slotKey`), never user-visible text, so
 * every row needs its own localized label; showing the raw name is what made this page read as
 * English on a Chinese ROM.
 */
data class IconSlotInfo(
    val slot: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
)

/**
 * Single source of truth for the slot list rendered by [SlotsSection].
 *
 * Labels reuse the same wording as the ported upstream slot list (network / connect / device /
 * other groups). Icons are Material rounded glyphs — the module's established icon language — so
 * the preview needs no bundled ROM artwork and cannot drift from a MIUI asset.
 */
object IconSlotCatalog {
    /** Rows shown in the settings page, in display order. */
    val slots: List<String> = listOf(
        "mobile", "no_sim", "airplane", "wifi", "demo_wifi", "hotspot", "vpn",
        "network_speed", "bluetooth", "bluetooth_handsfree_battery", "handle_battery",
        "nfc", "gps", "location", "wireless_headset", "phone", "pad", "pc",
        "sound_box_group", "stereo", "sound_box_screen", "sound_box", "tv", "glasses",
        "car", "camera", "dist_compute", "headset", "alarm_clock", "zen", "volume",
        "second_space", "compound_icon"
    ) + IconSlotPolicy.MODULE_SLOTS

    /** Catalog keyed by host slot name; `internal` so the unit test can assert full coverage. */
    internal val bySlot: Map<String, IconSlotInfo> = listOf(
        IconSlotInfo("mobile", R.string.icon_slot_mobile, Icons.Rounded.SignalCellularAlt),
        IconSlotInfo("no_sim", R.string.icon_slot_no_sim, Icons.Rounded.SimCardAlert),
        IconSlotInfo("airplane", R.string.icon_slot_airplane, Icons.Rounded.AirplanemodeActive),
        IconSlotInfo("wifi", R.string.icon_slot_wifi, Icons.Rounded.Wifi),
        IconSlotInfo("demo_wifi", R.string.icon_slot_demo_wifi, Icons.Rounded.Wifi),
        IconSlotInfo("hotspot", R.string.icon_slot_hotspot, Icons.Rounded.WifiTethering),
        IconSlotInfo("vpn", R.string.icon_slot_vpn, Icons.Rounded.VpnKey),
        IconSlotInfo("network_speed", R.string.icon_slot_network_speed, Icons.Rounded.Speed),
        IconSlotInfo("bluetooth", R.string.icon_slot_bluetooth, Icons.Rounded.Bluetooth),
        IconSlotInfo(
            "bluetooth_handsfree_battery",
            R.string.icon_slot_bluetooth_battery,
            Icons.Rounded.BluetoothConnected
        ),
        IconSlotInfo("handle_battery", R.string.icon_slot_handle_battery, Icons.Rounded.BatteryChargingFull),
        IconSlotInfo("nfc", R.string.icon_slot_nfc, Icons.Rounded.Nfc),
        IconSlotInfo("gps", R.string.icon_slot_gps, Icons.Rounded.GpsFixed),
        IconSlotInfo("location", R.string.icon_slot_location, Icons.Rounded.MyLocation),
        IconSlotInfo("wireless_headset", R.string.icon_slot_wireless_headset, Icons.Rounded.Headphones),
        IconSlotInfo("phone", R.string.icon_slot_phone, Icons.Rounded.Smartphone),
        IconSlotInfo("pad", R.string.icon_slot_pad, Icons.Rounded.TabletAndroid),
        IconSlotInfo("pc", R.string.icon_slot_pc, Icons.Rounded.Computer),
        IconSlotInfo("sound_box_group", R.string.icon_slot_sound_box_group, Icons.Rounded.SpeakerGroup),
        IconSlotInfo("stereo", R.string.icon_slot_stereo, Icons.Rounded.SurroundSound),
        IconSlotInfo("sound_box_screen", R.string.icon_slot_sound_box_screen, Icons.Rounded.SmartDisplay),
        IconSlotInfo("sound_box", R.string.icon_slot_sound_box, Icons.Rounded.Speaker),
        IconSlotInfo("tv", R.string.icon_slot_tv, Icons.Rounded.Tv),
        IconSlotInfo("glasses", R.string.icon_slot_glasses, Icons.Rounded.Visibility),
        IconSlotInfo("car", R.string.icon_slot_car, Icons.Rounded.DirectionsCar),
        IconSlotInfo("camera", R.string.icon_slot_camera, Icons.Rounded.CameraAlt),
        IconSlotInfo("dist_compute", R.string.icon_slot_dist_compute, Icons.Rounded.Memory),
        IconSlotInfo("headset", R.string.icon_slot_headset, Icons.Rounded.HeadsetMic),
        IconSlotInfo("alarm_clock", R.string.icon_slot_alarm_clock, Icons.Rounded.Alarm),
        IconSlotInfo("zen", R.string.icon_slot_zen, Icons.Rounded.DoNotDisturbOn),
        IconSlotInfo("volume", R.string.icon_slot_volume, Icons.AutoMirrored.Rounded.VolumeMute),
        IconSlotInfo("second_space", R.string.icon_slot_second_space, Icons.Rounded.Duo),
        IconSlotInfo("compound_icon", R.string.icon_slot_compound_icon, Icons.Rounded.Layers),
        IconSlotInfo(
            "stacked_mobile_icon",
            R.string.icon_slot_stacked_mobile_icon,
            Icons.Rounded.SignalCellularAlt2Bar
        ),
        IconSlotInfo("stacked_mobile_type", R.string.icon_slot_stacked_mobile_type, Icons.Rounded.TextFields),
        IconSlotInfo("single_mobile_sim1", R.string.icon_slot_single_sim1, Icons.Rounded.LooksOne),
        IconSlotInfo("single_mobile_sim2", R.string.icon_slot_single_sim2, Icons.Rounded.LooksTwo)
    ).associateBy(IconSlotInfo::slot)

    /** Preview metadata for a slot; null only for a slot added to the host list before this table. */
    fun of(slot: String): IconSlotInfo? = bySlot[slot]

    /** Used when a row has no catalog entry, so an unknown slot still renders readably. */
    fun fallbackIcon(): ImageVector = Icons.Rounded.Extension

    /** Last-resort label for an unknown slot: the wire name, prettified. */
    fun fallbackLabel(slot: String): String =
        slot.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
