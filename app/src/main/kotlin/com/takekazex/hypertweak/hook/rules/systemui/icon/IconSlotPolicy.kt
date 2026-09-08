package com.takekazex.hypertweak.hook.rules.systemui.icon

/** The surface whose icon list is being filtered. */
enum class IconSurface {
    STATUS_BAR,
    CONTROL_CENTER,
    UNKNOWN
}

/** Stable wire values used by the settings page and the old XiaomiHelper port. */
enum class IconSlotMode(val value: Int) {
    FOLLOW_SYSTEM(0),
    SHOW_EVERYWHERE(1),
    STATUS_BAR_ONLY(2),
    CONTROL_CENTER_ONLY(3),
    HIDE_EVERYWHERE(4);

    companion object {
        fun from(value: Int): IconSlotMode = entries.firstOrNull { it.value == value }
            ?: FOLLOW_SYSTEM
    }
}

data class IndexedIconSlot(val index: Int, val slot: String, val ordinal: Int)

/**
 * Values consumed by [IconSlotPolicy]. This is deliberately a plain data object so the policy can
 * be tested without Android, Preferences, or a host class loader.
 */
data class IconSlotPolicyConfig(
    val position: Int = POSITION_SYSTEM,
    val customOrderEntries: Set<String> = emptySet(),
    val reorderHidden: Boolean = false,
    val slotModes: Map<String, Int> = emptyMap(),
    val extraHiddenSlots: Set<String> = emptySet(),
    val enabledModuleSlots: Set<String> = emptySet(),
    val leftSlots: Set<String> = emptySet()
) {
    companion object {
        const val POSITION_SYSTEM = 0
        const val POSITION_WIFI_BEFORE_MOBILE = 1
        const val POSITION_CUSTOM = 2
    }
}

/**
 * Pure status-bar slot policy. It owns neither a View nor the host's mutable lists; callers pass a
 * snapshot in and receive a new stable list. Keeping this boundary pure is important because the
 * same policy is used for classic manager block lists, modern container ignored slots, and tests.
 */
object IconSlotPolicy {
    const val SLOT_MOBILE = "mobile"
    const val SLOT_WIFI = "wifi"
    const val SLOT_DEMO_WIFI = "demo_wifi"

    val SIGNAL_SLOTS: List<String> = listOf(
        "stacked_mobile_icon",
        "stacked_mobile_type",
        "single_mobile_sim1",
        "single_mobile_sim2"
    )

    val MODULE_SLOTS: List<String> = SIGNAL_SLOTS

    /**
     * These are preference-name aliases, not slot aliases in the host list. The first name is the
     * independent HyperTweak key; the following names are the shared/source keys used only when
     * the independent key is absent.
     */
    fun preferenceSlotAliases(slot: String): List<String> = when (slot) {
        SLOT_DEMO_WIFI -> listOf(SLOT_DEMO_WIFI, SLOT_WIFI)
        "gps" -> listOf("gps", "location")
        "network_speed" -> listOf("network_speed", "net_speed")
        "bluetooth_handsfree_battery" -> listOf(
            "bluetooth_handsfree_battery",
            "bluetooth_battery"
        )
        else -> listOf(slot)
    }

    /** Maps verified OS4 StatusBarLocation names without guessing unknown future values. */
    fun surfaceForHostLocation(locationName: String?): IconSurface = when (locationName) {
        "HOME", "KEYGUARD" -> IconSurface.STATUS_BAR
        "QS", "QS_FAKE", "SHADE_CARRIER_GROUP" -> IconSurface.CONTROL_CENTER
        else -> IconSurface.UNKNOWN
    }

    /**
     * Parses XiaomiHelper's legacy StringSet entries. A malformed entry is ignored, while the
     * caller's collection order is retained for equal indexes so sorting remains stable.
     */
    fun parseLegacyOrder(entries: Iterable<String>): List<IndexedIconSlot> = entries
        .mapIndexedNotNull { ordinal, raw ->
            val separator = raw.indexOf(':')
            if (separator <= 0 || separator == raw.lastIndex) return@mapIndexedNotNull null
            if (raw.indexOf(':', separator + 1) >= 0) return@mapIndexedNotNull null
            val index = raw.substring(0, separator).trim().toIntOrNull()
                ?: return@mapIndexedNotNull null
            val slot = raw.substring(separator + 1).trim()
            if (index < 0 || slot.isEmpty()) null else IndexedIconSlot(index, slot, ordinal)
        }
        .sortedWith(compareBy<IndexedIconSlot> { it.index }.thenBy { it.ordinal })

    /** Returns the first explicitly configured alias, with extra hiding taking precedence. */
    fun modeFor(
        slot: String,
        configuredModes: Map<String, Int>,
        extraHiddenSlots: Set<String> = emptySet()
    ): IconSlotMode {
        if (extraHiddenSlots.contains(slot)) return IconSlotMode.HIDE_EVERYWHERE
        val configured = preferenceSlotAliases(slot).firstNotNullOfOrNull(configuredModes::get)
        return IconSlotMode.from(configured ?: 0)
    }

    /**
     * Builds the host block/ignored list for one surface. Unknown locations are fail-open for the
     * two one-surface modes; explicit show/hide modes remain deterministic.
     */
    fun blockedFor(
        surface: IconSurface,
        systemSlots: List<String>,
        config: IconSlotPolicyConfig
    ): List<String> {
        val result = LinkedHashSet<String>()
        systemSlots.forEach { slot ->
            if (slot.isNotBlank()) result += slot
        }

        val candidates = LinkedHashSet<String>().apply {
            addAll(result)
            addAll(config.slotModes.keys)
            addAll(config.extraHiddenSlots)
        }
        candidates.forEach { slot ->
            when (modeFor(slot, config.slotModes, config.extraHiddenSlots)) {
                IconSlotMode.FOLLOW_SYSTEM -> Unit
                IconSlotMode.SHOW_EVERYWHERE -> result.remove(slot)
                IconSlotMode.STATUS_BAR_ONLY -> when (surface) {
                    IconSurface.STATUS_BAR -> result.remove(slot)
                    IconSurface.CONTROL_CENTER -> result += slot
                    IconSurface.UNKNOWN -> Unit
                }
                IconSlotMode.CONTROL_CENTER_ONLY -> when (surface) {
                    IconSurface.STATUS_BAR -> result += slot
                    IconSurface.CONTROL_CENTER -> result.remove(slot)
                    IconSurface.UNKNOWN -> Unit
                }
                IconSlotMode.HIDE_EVERYWHERE -> result += slot
            }
        }
        return result.toList()
    }

    /**
     * Normalizes one config list in place conceptually, but returns a fresh list and never mutates
     * the host's Slot objects. Module slots are added only for enabled features.
     */
    fun normalizeOrder(
        systemSlots: List<String>,
        config: IconSlotPolicyConfig
    ): List<String> {
        var normalized = addEnabledModuleSlots(systemSlots, config.enabledModuleSlots)
        normalized = when (config.position.coerceIn(0, 2)) {
            IconSlotPolicyConfig.POSITION_WIFI_BEFORE_MOBILE -> moveWifiBeforeMobile(normalized)
            IconSlotPolicyConfig.POSITION_CUSTOM -> applyCustomOrder(normalized, config.customOrderEntries)
            else -> normalized
        }
        return if (config.reorderHidden) {
            reorderHidden(normalized, hiddenSlots(config), SIGNAL_SLOTS.toSet())
        } else {
            normalized
        }
    }

    /** Hidden items precede visible items, with signal slots kept in their original group. */
    fun reorderHidden(
        slots: List<String>,
        hiddenSlots: Set<String>,
        signalSlots: Set<String> = SIGNAL_SLOTS.toSet()
    ): List<String> {
        val unique = stableUnique(slots)
        val hidden = unique.filter { it in hiddenSlots && it !in signalSlots }
        val remaining = unique.filterNot { it in hidden }
        return hidden + remaining
    }

    fun hiddenSlots(config: IconSlotPolicyConfig): Set<String> {
        val hidden = LinkedHashSet<String>()
        hidden += config.extraHiddenSlots
        hidden += config.leftSlots
        config.slotModes.keys.forEach { slot ->
            if (modeFor(slot, config.slotModes, config.extraHiddenSlots) == IconSlotMode.HIDE_EVERYWHERE) {
                hidden += slot
            }
        }
        return hidden
    }

    private fun addEnabledModuleSlots(
        systemSlots: List<String>,
        enabledModuleSlots: Set<String>
    ): List<String> {
        val result = stableUnique(systemSlots)
        val enabledSignals = SIGNAL_SLOTS.filter { it in enabledModuleSlots && it !in result }
        if (enabledSignals.isNotEmpty()) {
            val mobileIndex = result.indexOfFirst { it == SLOT_MOBILE || it == "demo_mobile" }
            result.addAll(if (mobileIndex >= 0) mobileIndex else result.size, enabledSignals)
        }
        return result
    }

    private fun moveWifiBeforeMobile(slots: List<String>): List<String> {
        val unique = stableUnique(slots)
        val wifi = unique.filter { it == SLOT_WIFI || it == SLOT_DEMO_WIFI }
        if (wifi.isEmpty()) return unique
        val withoutWifi = ArrayList(unique.filterNot { it in wifi })
        // A generated stacked/single slot is part of the mobile group even though the stock
        // config does not contain it. Keep the whole group together when Wi-Fi is fronted.
        val mobileIndex = withoutWifi.indexOfFirst {
            it == SLOT_MOBILE || it == "demo_mobile" || it in SIGNAL_SLOTS
        }
        withoutWifi.addAll(if (mobileIndex >= 0) mobileIndex else withoutWifi.size, wifi)
        return withoutWifi
    }

    private fun applyCustomOrder(slots: List<String>, entries: Iterable<String>): List<String> {
        val unique = stableUnique(slots)
        val known = unique.toSet()
        val selected = LinkedHashSet<String>()
        parseLegacyOrder(entries).forEach { entry ->
            if (entry.slot in known) selected += entry.slot
        }
        return selected.toList() + unique.filterNot(selected::contains)
    }

    private fun stableUnique(slots: List<String>): ArrayList<String> {
        val result = ArrayList<String>(slots.size)
        val seen = HashSet<String>()
        slots.forEach { slot ->
            if (slot.isNotBlank() && seen.add(slot)) result += slot
        }
        return result
    }
}
