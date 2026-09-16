package com.takekazex.hypertweak.ui.page

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PhoneMissed
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Duo
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LooksOne
import androidx.compose.material.icons.rounded.LooksTwo
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SignalCellularAlt2Bar
import androidx.compose.material.icons.rounded.SimCardAlert
import androidx.compose.material.icons.rounded.SmartButton
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
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.takekazex.hypertweak.R

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
    val icon: ImageVector,
    /**
     * False for a settings-only key that never occupies a position in the host's slot list, so the
     * order page must not offer it: it is a `Preferences` key for a group of slots, not a slot.
     */
    val orderable: Boolean = true
)

/**
 * Single source of truth for the slot list rendered by [SlotsSection] and [IconOrderPage].
 *
 * Labels reuse the same wording as the ported upstream slot list (network / connect / device /
 * other groups). Icons are Material rounded glyphs — the module's established icon language — so
 * the preview needs no bundled ROM artwork and cannot drift from a MIUI asset.
 *
 * [slots] is kept in SystemUI's own slot order (`config_statusBarIcons`, identical on the two OS4
 * builds in the reverse cache), with the module's signal slots where `IconSlotPolicy` inserts them
 * for a stacked-signal build, because the icon-order page shows this list as the slot order and
 * stores a prefix list: any host slot missing here would be pushed behind the whole list by the
 * first drag.
 */
object IconSlotCatalog {
    /** Rows shown in the settings page, in host order. */
    val slots: List<String> = listOf(
        "handle", "network_speed", "mute", "micphone", "headset", "mikey", "privacy_mode",
        "nfc", "gps", "missed_call", "managed_profile", "second_space", "ime", "cast",
        "location", "stealth", "tty", "alarm_clock", "vpn", "ethernet", "handle_battery",
        "bluetooth", "bluetooth_handsfree_battery", "hotspot", "sound_box_group", "stereo",
        "sound_box_screen", "sound_box", "wireless_headset", "zen", "volume", "dist_compute",
        "camera", "glasses", "car", "tv", "pc", "pad", "phone", "hd", "airplane",
        "stacked_mobile_icon", "stacked_mobile_type", "single_mobile_sim1", "single_mobile_sim2",
        "mobile", "demo_mobile", "no_sim", "wifi", "demo_wifi", "compound_icon"
    )

    /** Catalog keyed by host slot name; `internal` so the unit test can assert full coverage. */
    internal val bySlot: Map<String, IconSlotInfo> = listOf(
        IconSlotInfo("handle", R.string.icon_slot_handle, Icons.Rounded.Hd),
        IconSlotInfo("network_speed", R.string.icon_slot_network_speed, Icons.Rounded.Speed),
        IconSlotInfo("mute", R.string.icon_slot_mute, Icons.AutoMirrored.Rounded.VolumeOff),
        IconSlotInfo("micphone", R.string.icon_slot_micphone, Icons.Rounded.Mic),
        IconSlotInfo("headset", R.string.icon_slot_headset, Icons.Rounded.HeadsetMic),
        IconSlotInfo("mikey", R.string.icon_slot_mikey, Icons.Rounded.SmartButton),
        IconSlotInfo("privacy_mode", R.string.icon_slot_privacy_mode, Icons.Rounded.PrivacyTip),
        IconSlotInfo("nfc", R.string.icon_slot_nfc, Icons.Rounded.Nfc),
        IconSlotInfo("gps", R.string.icon_slot_gps, Icons.Rounded.GpsFixed),
        IconSlotInfo("missed_call", R.string.icon_slot_missed_call, Icons.AutoMirrored.Rounded.PhoneMissed),
        IconSlotInfo("managed_profile", R.string.icon_slot_managed_profile, Icons.Rounded.Work),
        IconSlotInfo("second_space", R.string.icon_slot_second_space, Icons.Rounded.Duo),
        IconSlotInfo("ime", R.string.icon_slot_ime, Icons.Rounded.Keyboard),
        IconSlotInfo("cast", R.string.icon_slot_cast, Icons.Rounded.Cast),
        IconSlotInfo("location", R.string.icon_slot_location, Icons.Rounded.MyLocation),
        IconSlotInfo("stealth", R.string.icon_slot_stealth, Icons.Rounded.VisibilityOff),
        IconSlotInfo("tty", R.string.icon_slot_tty, Icons.Rounded.Hearing),
        IconSlotInfo("alarm_clock", R.string.icon_slot_alarm_clock, Icons.Rounded.Alarm),
        IconSlotInfo("vpn", R.string.icon_slot_vpn, Icons.Rounded.VpnKey),
        IconSlotInfo("ethernet", R.string.icon_slot_ethernet, Icons.Rounded.SettingsEthernet),
        IconSlotInfo("handle_battery", R.string.icon_slot_handle_battery, Icons.Rounded.BatteryChargingFull),
        IconSlotInfo("bluetooth", R.string.icon_slot_bluetooth, Icons.Rounded.Bluetooth),
        IconSlotInfo(
            "bluetooth_handsfree_battery",
            R.string.icon_slot_bluetooth_battery,
            Icons.Rounded.BluetoothConnected
        ),
        IconSlotInfo("hotspot", R.string.icon_slot_hotspot, Icons.Rounded.WifiTethering),
        IconSlotInfo("sound_box_group", R.string.icon_slot_sound_box_group, Icons.Rounded.SpeakerGroup),
        IconSlotInfo("stereo", R.string.icon_slot_stereo, Icons.Rounded.SurroundSound),
        IconSlotInfo("sound_box_screen", R.string.icon_slot_sound_box_screen, Icons.Rounded.SmartDisplay),
        IconSlotInfo("sound_box", R.string.icon_slot_sound_box, Icons.Rounded.Speaker),
        IconSlotInfo("wireless_headset", R.string.icon_slot_wireless_headset, Icons.Rounded.Headphones),
        IconSlotInfo("zen", R.string.icon_slot_zen, Icons.Rounded.DoNotDisturbOn),
        IconSlotInfo("volume", R.string.icon_slot_volume, Icons.AutoMirrored.Rounded.VolumeMute),
        IconSlotInfo("dist_compute", R.string.icon_slot_dist_compute, Icons.Rounded.Memory),
        IconSlotInfo("camera", R.string.icon_slot_camera, Icons.Rounded.CameraAlt),
        IconSlotInfo("glasses", R.string.icon_slot_glasses, Icons.Rounded.Visibility),
        IconSlotInfo("car", R.string.icon_slot_car, Icons.Rounded.DirectionsCar),
        IconSlotInfo("tv", R.string.icon_slot_tv, Icons.Rounded.Tv),
        IconSlotInfo("pc", R.string.icon_slot_pc, Icons.Rounded.Computer),
        IconSlotInfo("pad", R.string.icon_slot_pad, Icons.Rounded.TabletAndroid),
        IconSlotInfo("phone", R.string.icon_slot_phone, Icons.Rounded.Smartphone),
        IconSlotInfo("hd", R.string.icon_slot_hd, Icons.Rounded.HighQuality),
        IconSlotInfo("airplane", R.string.icon_slot_airplane, Icons.Rounded.AirplanemodeActive),
        IconSlotInfo(
            "stacked_mobile_icon",
            R.string.icon_slot_stacked_mobile_icon,
            Icons.Rounded.SignalCellularAlt2Bar
        ),
        IconSlotInfo("stacked_mobile_type", R.string.icon_slot_stacked_mobile_type, Icons.Rounded.TextFields),
        IconSlotInfo("single_mobile_sim1", R.string.icon_slot_single_sim1, Icons.Rounded.LooksOne),
        IconSlotInfo("single_mobile_sim2", R.string.icon_slot_single_sim2, Icons.Rounded.LooksTwo),
        IconSlotInfo("mobile", R.string.icon_slot_mobile, Icons.Rounded.SignalCellularAlt),
        IconSlotInfo("demo_mobile", R.string.icon_slot_demo_mobile, Icons.Rounded.SignalCellularAlt),
        IconSlotInfo("no_sim", R.string.icon_slot_no_sim, Icons.Rounded.SimCardAlert),
        IconSlotInfo("wifi", R.string.icon_slot_wifi, Icons.Rounded.Wifi),
        IconSlotInfo("demo_wifi", R.string.icon_slot_demo_wifi, Icons.Rounded.Wifi),
        IconSlotInfo(
            "compound_icon",
            R.string.icon_slot_compound_icon,
            Icons.Rounded.Layers,
            orderable = false
        )
    ).associateBy(IconSlotInfo::slot)

    /** Slots [IconOrderPage] can order: the host's own slots plus the module's signal slots. */
    val orderSlots: List<String> = slots.filterNot { bySlot[it]?.orderable == false }

    /** Preview metadata for a slot; null only for a slot added to the host list before this table. */
    fun of(slot: String): IconSlotInfo? = bySlot[slot]

    /** Used when a row has no catalog entry, so an unknown slot still renders readably. */
    fun fallbackIcon(): ImageVector = Icons.Rounded.Extension

    /** Last-resort label for an unknown slot: the wire name, prettified. */
    fun fallbackLabel(slot: String): String =
        slot.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
