package com.takekazex.hypertweak.settings.contract

internal object IconSettingsPreferencePolicy {
    fun keepCellularTypeOnWifi(read: (String) -> Boolean?): Boolean =
        read(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI)
            ?: (read(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI) == false)

    fun hideNonDefaultSim(read: (String) -> Boolean?): Boolean =
        read(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM)
            ?: (read(IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO) == true)

    fun iconBoolean(key: String, read: (String) -> Boolean?): Boolean {
        val legacy = when (key) {
            IconSettingsKeys.KEY_ICON_STACKED_ENABLED -> "icon_tuner_stacked_enabled"
            IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE -> "statusbar_ignore_sys_hide"
            IconSettingsKeys.KEY_ICON_HIDE_PRIVACY -> "icon_tuner_hide_privacy"
            IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED -> LEGACY_LEFT_CONTAINER
            else -> IconTunerContract.leftPreferenceSlots[key]
        }
        return read(key) ?: legacy?.let(read) ?: false
    }

    fun extraHiddenSlots(read: (String) -> String?): List<String> =
        IconSlotPolicy.parseSlotList(read(IconSettingsKeys.KEY_ICON_EXT_BLOCKED) ?: read(LEGACY_EXTRA_BLOCKED).orEmpty())

    const val SLOT_KEY_PREFIX = "icon_tuner_slot_"

    fun slotKey(slot: String): String = SLOT_KEY_PREFIX + slot

    const val LEGACY_EXTRA_BLOCKED = "icon_tuner_ext_blocked"
    const val LEGACY_LEFT_CONTAINER = "icon_tuner_left_container"

    fun slotModes(read: (String) -> Int?, extraHidden: Iterable<String>): Map<String, Int> {
        val modes = IconSlotPolicy.configurableSlots.mapNotNull { slot ->
            IconSlotPolicy.preferenceSlotAliases(slot).firstNotNullOfOrNull(read)?.let { slot to it }
        }.toMap()
        return IconSlotPolicy.foldExtraHiddenIntoModes(modes, extraHidden)
    }

    fun leftMode(mode: Int?, legacyEnabled: Boolean): Int =
        (mode ?: if (legacyEnabled) IconTunerContract.LEFT_MODE_HOME else IconTunerContract.LEFT_MODE_DISABLED)
            .coerceIn(IconTunerContract.LEFT_MODE_DISABLED, IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN)

    fun wifiStandardMode(mode: Int?, legacyHide: Boolean): Int =
        (mode ?: if (legacyHide) 1 else 0).coerceIn(0, 3)

    fun ccBatteryPercentStyle(mode: Int?, outside: Boolean, leading: Boolean): CcBatteryPercentStyle {
        if (mode != null) return CcBatteryPercentStyle.entries.getOrNull(mode) ?: CcBatteryPercentStyle.SYSTEM
        return when {
            leading -> CcBatteryPercentStyle.OUTSIDE_LEADING
            outside -> CcBatteryPercentStyle.OUTSIDE_TRAILING
            else -> CcBatteryPercentStyle.SYSTEM
        }
    }

    fun nativeRoamingMode(mode: Int?, regular: Boolean, small: Boolean, global: Boolean): Int =
        mode?.coerceIn(0, 3) ?: ((if (regular || global) 1 else 0) + (if (small || global) 2 else 0))

    /** Null requires the shared icon transaction. An empty set means no stored value changed. */
    fun independentReloadDomains(
        before: Map<String, Any>?, after: Map<String, Any>?
    ): Set<IndependentIconDomain>? {
        if (before == null || after == null) return null
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        return changed.mapTo(mutableSetOf()) { key ->
            when (key) {
                PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING -> IndependentIconDomain.SAMPLING
                PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_MAX,
                PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_ICON_MAX -> IndependentIconDomain.NOTIFICATIONS
                else -> return null
            }
        }
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

internal enum class IndependentIconDomain { SAMPLING, NOTIFICATIONS }
