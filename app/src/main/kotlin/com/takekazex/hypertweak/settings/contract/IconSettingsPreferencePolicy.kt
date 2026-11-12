package com.takekazex.hypertweak.settings.contract

internal object IconSettingsPreferencePolicy {
    const val TYPE_ORDER_DEFAULT = 0
    const val TYPE_ORDER_SWAP = 1
    const val TYPE_ORDER_RIGHT = 2
    const val TYPE_ORDER_LEFT = 3

    // Legacy-key fallbacks live in IconConfigMigration; values reaching the helpers below are
    // already migrated by IconConfigDecoder.

    fun extraHiddenSlots(read: (String) -> String?): List<String> =
        IconSlotPolicy.parseSlotList(read(IconSettingsKeys.KEY_ICON_EXT_BLOCKED).orEmpty())

    const val SLOT_KEY_PREFIX = "icon_tuner_slot_"

    fun slotKey(slot: String): String = SLOT_KEY_PREFIX + slot

    const val LEGACY_EXTRA_BLOCKED = "icon_tuner_ext_blocked"
    const val LEGACY_LEFT_CONTAINER = "icon_tuner_left_container"

    fun slotModes(read: (String) -> Int?, extraHidden: Iterable<String>): Map<String, Int> {
        val modes = IconSlotPolicy.configurableSlots.mapNotNull { slot ->
            read(slot)?.let { slot to it }
        }.toMap()
        return IconSlotPolicy.foldExtraHiddenIntoModes(modes, extraHidden)
    }


    fun affectsIcons(key: String?): Boolean = key == null || key == "prefs_epoch" ||
        key.startsWith("icon_") || key.startsWith("stacked_s_") ||
        key.startsWith("systemui_cc_") || key.startsWith("statusbar_hide_carrier_") ||
        key.startsWith("systemui_ls_hide_carrier_") || key == "statusbar_region_sampling" ||
        key == "statusbar_notif_max" || key == "statusbar_notif_icon_max" ||
        key == "statusbar_ignore_sys_hide"
}

/** Stable preference values in dropdown order. */
internal enum class CcBatteryPercentStyle { SYSTEM, OUTSIDE_TRAILING, OUTSIDE_LEADING }

internal enum class IndependentIconDomain {
    SAMPLING,
    NOTIFICATIONS,
    /** Slot visibility, left placement and the control-center row split, applied in place. */
    POLICY,
    /** Duo options; the living Duo bindings reconcile in place. */
    DUO,
    /** Header, carrier block and carrier labels; control-center/lock-screen owners only. */
    CONTROL_CENTER_HEADER,
    CC_BATTERY,
    ROTATION_LOCK,
    AIRPLANE,
    /** Cellular accessory gates; the bound binders re-emit in place. */
    CELLULAR,
    /** Wi-Fi gates and override flows re-publish; padding and placement re-apply in place. */
    WIFI,
    /** Native SIM masks and the stacked bars; the mobile-signal owner re-applies both in place. */
    MOBILE_SIGNAL,
    /** Native type text/order/typography; that hook reinstalls alone without a group rebuild. */
    CELLULAR_TYPE
}
