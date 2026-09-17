package com.takekazex.hypertweak.ui.page

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
    /**
     * HyperOS status-bar glyph (a tintable vector). Sourced from Hyper Helper's bundled
     * `ic_stat_sys_*` foreground layers, with the rounded-square tile background removed so the
     * artwork matches what the status bar actually draws.
     */
    @DrawableRes val iconRes: Int,
    /**
     * False for a settings-only key that never occupies a position in the host's slot list, so the
     * order page must not offer it: it is a `Preferences` key for a group of slots, not a slot.
     */
    val orderable: Boolean = true
)

/**
 * Single source of truth for the slot list rendered by `SlotsSection` and `IconOrderPage`.
 *
 * Labels reuse the same wording as the ported upstream slot list (network / connect / device /
 * other groups). Icons are the upstream `ic_stat_sys_*` vectors, so the module uses the same
 * HyperOS glyphs Hyper Helper does instead of Material placeholders.
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
        IconSlotInfo("handle", R.string.icon_slot_handle, R.drawable.ic_stat_sys_unknown),
        IconSlotInfo("network_speed", R.string.icon_slot_network_speed, R.drawable.ic_stat_sys_net_speed),
        IconSlotInfo("mute", R.string.icon_slot_mute, R.drawable.ic_stat_sys_volume),
        IconSlotInfo("micphone", R.string.icon_slot_micphone, R.drawable.ic_stat_sys_micphone),
        IconSlotInfo("headset", R.string.icon_slot_headset, R.drawable.ic_stat_sys_headset),
        IconSlotInfo("mikey", R.string.icon_slot_mikey, R.drawable.ic_stat_sys_unknown),
        IconSlotInfo("privacy_mode", R.string.icon_slot_privacy_mode, R.drawable.ic_stat_sys_privacy_notice),
        IconSlotInfo("nfc", R.string.icon_slot_nfc, R.drawable.ic_stat_sys_nfc),
        IconSlotInfo("gps", R.string.icon_slot_gps, R.drawable.ic_stat_sys_location),
        IconSlotInfo("missed_call", R.string.icon_slot_missed_call, R.drawable.ic_stat_sys_unknown),
        IconSlotInfo("managed_profile", R.string.icon_slot_managed_profile, R.drawable.ic_stat_sys_managed_profile),
        IconSlotInfo("second_space", R.string.icon_slot_second_space, R.drawable.ic_stat_sys_second_space),
        IconSlotInfo("ime", R.string.icon_slot_ime, R.drawable.ic_stat_sys_unknown),
        IconSlotInfo("cast", R.string.icon_slot_cast, R.drawable.ic_stat_sys_unknown),
        IconSlotInfo("location", R.string.icon_slot_location, R.drawable.ic_stat_sys_location),
        IconSlotInfo("stealth", R.string.icon_slot_stealth, R.drawable.ic_stat_sys_stealth),
        IconSlotInfo("tty", R.string.icon_slot_tty, R.drawable.ic_stat_sys_tty),
        IconSlotInfo("alarm_clock", R.string.icon_slot_alarm_clock, R.drawable.ic_stat_sys_alarm_clock),
        IconSlotInfo("vpn", R.string.icon_slot_vpn, R.drawable.ic_stat_sys_vpn),
        IconSlotInfo("ethernet", R.string.icon_slot_ethernet, R.drawable.ic_stat_sys_unknown),
        IconSlotInfo("handle_battery", R.string.icon_slot_handle_battery, R.drawable.ic_stat_sys_handle_battery),
        IconSlotInfo("bluetooth", R.string.icon_slot_bluetooth, R.drawable.ic_stat_sys_bluetooth),
        IconSlotInfo(
            "bluetooth_handsfree_battery",
            R.string.icon_slot_bluetooth_battery,
            R.drawable.ic_stat_sys_bluetooth_handsfree_battery
        ),
        IconSlotInfo("hotspot", R.string.icon_slot_hotspot, R.drawable.ic_stat_sys_hotspot),
        IconSlotInfo("sound_box_group", R.string.icon_slot_sound_box_group, R.drawable.ic_stat_sys_sound_box_group),
        IconSlotInfo("stereo", R.string.icon_slot_stereo, R.drawable.ic_stat_sys_stereo),
        IconSlotInfo("sound_box_screen", R.string.icon_slot_sound_box_screen, R.drawable.ic_stat_sys_sound_box_screen),
        IconSlotInfo("sound_box", R.string.icon_slot_sound_box, R.drawable.ic_stat_sys_sound_box),
        IconSlotInfo("wireless_headset", R.string.icon_slot_wireless_headset, R.drawable.ic_stat_sys_wireless_headset),
        IconSlotInfo("zen", R.string.icon_slot_zen, R.drawable.ic_stat_sys_zen),
        IconSlotInfo("volume", R.string.icon_slot_volume, R.drawable.ic_stat_sys_volume),
        IconSlotInfo("dist_compute", R.string.icon_slot_dist_compute, R.drawable.ic_stat_sys_dist_compute),
        IconSlotInfo("camera", R.string.icon_slot_camera, R.drawable.ic_stat_sys_camera),
        IconSlotInfo("glasses", R.string.icon_slot_glasses, R.drawable.ic_stat_sys_glasses),
        IconSlotInfo("car", R.string.icon_slot_car, R.drawable.ic_stat_sys_car),
        IconSlotInfo("tv", R.string.icon_slot_tv, R.drawable.ic_stat_sys_tv),
        IconSlotInfo("pc", R.string.icon_slot_pc, R.drawable.ic_stat_sys_pc),
        IconSlotInfo("pad", R.string.icon_slot_pad, R.drawable.ic_stat_sys_pad),
        IconSlotInfo("phone", R.string.icon_slot_phone, R.drawable.ic_stat_sys_phone),
        IconSlotInfo("hd", R.string.icon_slot_hd, R.drawable.ic_stat_sys_speech_hd),
        IconSlotInfo("airplane", R.string.icon_slot_airplane, R.drawable.ic_stat_sys_airplane),
        IconSlotInfo(
            "stacked_mobile_icon",
            R.string.icon_slot_stacked_mobile_icon,
            R.drawable.ic_stat_sys_stacked_icon
        ),
        IconSlotInfo("stacked_mobile_type", R.string.icon_slot_stacked_mobile_type, R.drawable.ic_stat_sys_stacked_type),
        IconSlotInfo("single_mobile_sim1", R.string.icon_slot_single_sim1, R.drawable.ic_stat_sys_single_sim1),
        IconSlotInfo("single_mobile_sim2", R.string.icon_slot_single_sim2, R.drawable.ic_stat_sys_single_sim2),
        IconSlotInfo("mobile", R.string.icon_slot_mobile, R.drawable.ic_stat_sys_mobile),
        IconSlotInfo("demo_mobile", R.string.icon_slot_demo_mobile, R.drawable.ic_stat_sys_mobile),
        IconSlotInfo("no_sim", R.string.icon_slot_no_sim, R.drawable.ic_stat_sys_no_sim),
        IconSlotInfo("wifi", R.string.icon_slot_wifi, R.drawable.ic_stat_sys_wifi),
        IconSlotInfo("demo_wifi", R.string.icon_slot_demo_wifi, R.drawable.ic_stat_sys_wifi),
        IconSlotInfo(
            "compound_icon",
            R.string.icon_slot_compound_icon,
            R.drawable.ic_stat_sys_compound,
            orderable = false
        )
    ).associateBy(IconSlotInfo::slot)

    /** Slots [IconOrderPage] can order: the host's own slots plus the module's signal slots. */
    val orderSlots: List<String> = slots.filterNot { bySlot[it]?.orderable == false }

    /** Preview metadata for a slot; null only for a slot added to the host list before this table. */
    fun of(slot: String): IconSlotInfo? = bySlot[slot]

    /** Used when a row has no catalog entry, so an unknown slot still renders readably. */
    @DrawableRes
    fun fallbackIconRes(): Int = R.drawable.ic_stat_sys_unknown

    /** Last-resort label for an unknown slot: the wire name, prettified. */
    fun fallbackLabel(slot: String): String =
        slot.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
