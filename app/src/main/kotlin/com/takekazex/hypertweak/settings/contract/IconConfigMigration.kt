package com.takekazex.hypertweak.settings.contract

/**
 * Legacy-key fallbacks for [IconConfigDecoder], the only place they are implemented.
 *
 * Each rule is: an explicitly stored current key wins, even when it holds the default; only an
 * absent current key falls back to the retired keys it replaced. Returning null means "not
 * stored", so the decoder applies the section default. The runtime hooks, the settings page,
 * the preview and the debug catalog all read through this one wrapper.
 *
 * Retired keys migrate through [LAST_MIGRATING_VERSION] only. The first release after it deletes
 * this object, its callers' `legacyType` lookups and its tests; `IconConfigMigrationTest` fails
 * once the module version passes it, so the removal cannot be forgotten.
 */
internal object IconConfigMigration {
    const val LAST_MIGRATING_VERSION = "1.9.4"

    /** Pre-0/1 stacked SVG style key; its value 3 selected the surviving iOS family. */
    const val LEGACY_STACKED_SVG_STYLE = "icon_tuner_stacked_icon_svg_stacked"
    const val LEGACY_STACKED_ENABLED = "icon_tuner_stacked_enabled"
    const val LEGACY_IGNORE_SYS_HIDE = "statusbar_ignore_sys_hide"
    const val LEGACY_HIDE_PRIVACY = "icon_tuner_hide_privacy"
    const val LEGACY_CC_HIDE_CARRIER_NAME = "icon_cc_hide_carrier_name"

    private const val TYPE_ORDER_RIGHT = 2
    private const val WIFI_STANDARD_HIDDEN = 1

    private val legacyBooleans: Set<String> = buildSet {
        add(IconSettingsKeys.KEY_ICON_DUO_ENABLED)
        add(IconSettingsKeys.KEY_ICON_STACKED_ENABLED)
        add(LEGACY_STACKED_ENABLED)
        add(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP)
        add(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI)
        add(IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO)
        add(LEGACY_IGNORE_SYS_HIDE)
        add(LEGACY_HIDE_PRIVACY)
        add(LEGACY_CC_HIDE_CARRIER_NAME)
        addAll(IconTunerContract.leftPreferenceSlots.values)
        add(IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED)
        add(IconSettingsPreferencePolicy.LEGACY_LEFT_CONTAINER)
        add(IconSettingsKeys.KEY_ICON_HIDE_WIFI_TYPE)
        add(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_LEFT)
        add(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_OUTSIDE)
        add(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM)
        add(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM)
        add(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL)
    }

    /**
     * A value of the stored type of a retired key, for typed stores that cannot return a raw
     * value; null for keys this migration never reads.
     */
    fun legacyType(key: String): Any? = when {
        key in legacyBooleans -> false
        key == LEGACY_STACKED_SVG_STYLE -> 0
        key == IconSettingsPreferencePolicy.LEGACY_EXTRA_BLOCKED -> ""
        key.startsWith(IconSettingsPreferencePolicy.SLOT_KEY_PREFIX) -> 0
        else -> null
    }

    /** Wraps a raw store [read] so every decoded key sees its migrated value. */
    fun reader(read: (String) -> Any?): (String) -> Any? = { key -> resolve(key, read) }

    private fun resolve(key: String, read: (String) -> Any?): Any? {
        read(key)?.let { return migrateSlotReferences(key, it) }
        return when {
            key == IconSettingsKeys.KEY_ICON_CC_CARRIER_HIDING ->
                CarrierRowVisibility.BOTH.takeIf { read(LEGACY_CC_HIDE_CARRIER_NAME) == true }
            key == IconSettingsKeys.KEY_ICON_SIM_ONE_VISIBILITY -> legacySimMode(read,
                IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, IconSettingsKeys.KEY_ICON_HIDE_CARRIER_ONE)
            key == IconSettingsKeys.KEY_ICON_SIM_TWO_VISIBILITY -> legacySimMode(read,
                IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO, IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO)
            key == IconSettingsKeys.KEY_ICON_NON_DEFAULT_SIM_VISIBILITY ->
                SimVisibilityPolicy.STATUS_BAR_ONLY.takeIf {
                    (read(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM)
                        ?: read(IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO)) == true
                }
            key == IconSettingsKeys.KEY_ICON_SIGNAL_STYLE -> legacySignalStyle(read)
            key == IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE ->
                if (read(LEGACY_STACKED_SVG_STYLE) == 3) SignalSvgStyle.IOS27.storedValue else null
            key == IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER ->
                TYPE_ORDER_RIGHT.takeIf { read(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP) == true }
            // The retired switch meant the opposite and defaulted to showing the type, so an
            // explicit false there was the user turning hiding off: keep showing.
            key == IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI ->
                true.takeIf { read(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI) == false }
            key == IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM ->
                true.takeIf { read(IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO) == true }
            key == IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE -> read(LEGACY_IGNORE_SYS_HIDE) as? Boolean
            key == IconSettingsKeys.KEY_ICON_HIDE_PRIVACY -> read(LEGACY_HIDE_PRIVACY) as? Boolean
            key == IconSettingsKeys.KEY_ICON_EXT_BLOCKED ->
                read(IconSettingsPreferencePolicy.LEGACY_EXTRA_BLOCKED)?.let { migrateSlotReferences(key, it) }
            key in IconTunerContract.leftPreferenceSlots ->
                IconTunerContract.leftPreferenceSlots[key]?.let(read) as? Boolean
            key == IconTunerContract.KEY_LEFT_MODE -> IconTunerContract.LEFT_MODE_HOME.takeIf {
                (read(IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED)
                    ?: read(IconSettingsPreferencePolicy.LEGACY_LEFT_CONTAINER)) == true
            }
            key == IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE ->
                WIFI_STANDARD_HIDDEN.takeIf { read(IconSettingsKeys.KEY_ICON_HIDE_WIFI_TYPE) == true }
            key == SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE -> when {
                read(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_LEFT) == true ->
                    CcBatteryPercentStyle.OUTSIDE_LEADING.ordinal
                read(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_OUTSIDE) == true ->
                    CcBatteryPercentStyle.OUTSIDE_TRAILING.ordinal
                else -> null
            }
            key == IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE -> legacyRoamingMode(read)
            key.startsWith(IconSettingsPreferencePolicy.SLOT_KEY_PREFIX) -> legacySlotMode(key, read)
            else -> null
        }
    }

    /** Only persisted references migrate; foreign holders using the old name remain foreign. */
    private fun migrateSlotReferences(key: String, value: Any): Any {
        return when (key) {
            IconTunerContract.KEY_POSITION_VALUES, SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES -> {
                val entries = value as? Set<*> ?: return value
                val hasCurrent = entries.any { it is String && indexedSlot(it) == IconSlotPolicy.SLOT_STACKED_SIGNAL }
                entries.mapNotNullTo(linkedSetOf()) { entry ->
                    if (entry !is String || indexedSlot(entry) != IconSlotPolicy.LEGACY_STACKED_SIGNAL) entry
                    else if (hasCurrent) null
                    else entry.substringBefore(':') + ":" + IconSlotPolicy.SLOT_STACKED_SIGNAL
                }
            }
            IconSettingsKeys.KEY_ICON_EXT_BLOCKED -> (value as? String)?.split(',')
                ?.joinToString(",") { if (it.trim() == IconSlotPolicy.LEGACY_STACKED_SIGNAL)
                    IconSlotPolicy.SLOT_STACKED_SIGNAL else it } ?: value
            else -> value
        }
    }

    private fun indexedSlot(entry: String): String? = entry.substringAfter(':', "")
        .takeIf { entry.substringBefore(':').toIntOrNull() != null }

    private fun legacySimMode(read: (String) -> Any?, signal: String, carrier: String): Int? = when {
        read(carrier) == true -> SimVisibilityPolicy.ALL
        read(signal) == true -> SimVisibilityPolicy.STATUS_BAR_ONLY
        else -> null
    }

    /** Duo wins over stacked, as the runtime did when both retired switches were on. */
    private fun legacySignalStyle(read: (String) -> Any?): Int? = when {
        read(IconSettingsKeys.KEY_ICON_DUO_ENABLED) == true -> SignalStyle.DUO.ordinal
        (read(IconSettingsKeys.KEY_ICON_STACKED_ENABLED) ?: read(LEGACY_STACKED_ENABLED)) == true ->
            SignalStyle.STACKED.ordinal
        else -> null
    }

    /** Bit 1 hides the regular roaming badge, bit 2 the small one; "global" set both. */
    private fun legacyRoamingMode(read: (String) -> Any?): Int? {
        val regular = read(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM) == true
        val small = read(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM) == true
        val global = read(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL) == true
        val mode = (if (regular || global) 1 else 0) + (if (small || global) 2 else 0)
        return mode.takeIf { it != 0 }
    }

    /** A slot may have been stored under an older host name, such as `location` for `gps`. */
    private fun legacySlotMode(key: String, read: (String) -> Any?): Int? {
        val slot = key.removePrefix(IconSettingsPreferencePolicy.SLOT_KEY_PREFIX)
        return IconSlotPolicy.preferenceSlotAliases(slot)
            .asSequence()
            .filter { it != slot }
            .firstNotNullOfOrNull { read(IconSettingsPreferencePolicy.slotKey(it)) as? Int }
    }
}
