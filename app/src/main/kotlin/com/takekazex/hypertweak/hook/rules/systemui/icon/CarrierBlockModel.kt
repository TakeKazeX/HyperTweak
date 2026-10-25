package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.MobileSignalState

/**
 * One row of the two-line control-center carrier block.
 *
 * Cellular strength stays at the row's start. Cellular type and Wi-Fi share one trailing measured
 * position by default; the explicit keep-type choice reserves room for both within that position.
 */
data class CarrierRowModel(
    val slot: Int,
    val subId: Int? = null,
    /** Type provenance may differ from the signal/name SIM when network status is on row one. */
    val networkSubId: Int? = subId,
    /** `renderLevel` of this SIM (`-1` = no service), or null when no honest level exists. */
    val signalLevel: Int? = null,
    /** Network type text ("5G", "4G+…") for this row, or null when it should not be drawn. */
    val typeText: String? = null,
    /** Wi-Fi level 0..4; follows the active data subscription. */
    val wifiLevel: Int? = null,
    /** Wi-Fi is default, so the cellular type fades out unless explicitly retained. */
    val typeSuppressed: Boolean = false,
    val airplane: Boolean = false
) {
    /** A subscription owns its row; airplane mode owns the first row independently of SIMs. */
    val visible: Boolean get() = subId != null || airplane

    val hasSignal: Boolean get() = visible && signalLevel != null
    val hasType: Boolean get() = visible && !typeText.isNullOrEmpty()
    val hasWifi: Boolean get() = visible && wifiLevel != null
}

/** User choices that change only the row mapping, never the host data. */
data class CarrierBlockConfig(
    /** Switch 4: draw the network type of the SIM that is not the default data line too. */
    val showNonDataType: Boolean = false,
    /** 连接 WiFi 时依旧显示蜂窝类型: keep the type readable while Wi-Fi carries the data. */
    val keepTypeOnWifi: Boolean = false,
    val dataFirst: Boolean = false,
    val networkOnFirstRow: Boolean = false
)

/**
 * Pure mapping from the shared mobile reducer to the two carrier rows.
 *
 * `slotOf` resolves a subscription id to its physical SIM slot; it is injected so this policy keeps
 * working (and stays unit-testable) without `SubscriptionManager`. Physical slot identity is
 * retained when the data-first style reorders the rows.
 * Subscription-flow order is never used as a substitute for physical slot identity.
 */
object CarrierBlockPolicy {
    /** Custom operator names belong to physical slots, SIM names to subscription identities. */
    fun carrierName(customName: CharSequence?, simName: CharSequence?, nativeName: CharSequence): CharSequence =
        customName?.takeIf { it.isNotBlank() }
            ?: simName?.takeIf { it.isNotBlank() }
            ?: nativeName

    /** The control-center carrier layout owns exactly two rows. */
    const val ROW_COUNT = 2

    /** Returned by [slotOf] when a subscription has no usable slot index. */
    const val INVALID_SLOT = -1

    fun resolve(
        state: MobileSignalState,
        wifiLevel: Int?,
        config: CarrierBlockConfig,
        slotOf: (Int) -> Int
    ): List<CarrierRowModel> = (0 until ROW_COUNT).map { slot ->
        if (state.airplaneMode) {
            return@map CarrierRowModel(slot = slot, airplane = slot == 0,
                wifiLevel = wifiLevel?.coerceIn(0, 4)?.takeIf { slot == 0 })
        }
        val subId = state.subscriptionOrder.firstOrNull { candidate ->
            runCatching { slotOf(candidate) }.getOrDefault(INVALID_SLOT) == slot
        }
        if (subId == null) {
            return@map CarrierRowModel(slot = slot)
        }
        val subscription = state.subscriptions[subId]
        val signalLevel = subscription
            ?.takeIf { !state.airplaneMode && it.supportsReplacement }
            ?.renderLevel
        val typeText = subscription
            ?.takeIf { !state.airplaneMode && it.inService != false &&
                (state.subscriptions[state.activeDataSubId]?.dataConnected == true || state.wifiConnected) }
            ?.networkType
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { subId == state.activeDataSubId || config.showNonDataType }
        // Wi-Fi follows the selected data SIM in either row style.
        val rowWifi = wifiLevel
            ?.coerceIn(0, 4)
            ?.takeIf { subId == state.activeDataSubId && state.wifiConnected }
        CarrierRowModel(
            slot = slot,
            subId = subId,
            signalLevel = signalLevel,
            typeText = typeText,
            wifiLevel = rowWifi,
            // Suppression belongs to the default route, including the row without a Wi-Fi glyph.
            typeSuppressed = typeText != null && state.wifiConnected && !config.keepTypeOnWifi
        )
    }.let { rows ->
        if (config.dataFirst && !state.airplaneMode) rows.sortedBy {
            if (it.subId == state.activeDataSubId) -1 else it.slot
        } else if (config.networkOnFirstRow && !state.airplaneMode) {
            val first = rows.firstOrNull { it.visible }
            val active = rows.firstOrNull { it.subId == state.activeDataSubId }
            if (first == null || active == null || first.slot == active.slot) rows else rows.map { row ->
                val network = when (row.slot) {
                    first.slot -> active
                    active.slot -> first
                    else -> row
                }
                row.copy(networkSubId = network.networkSubId, typeText = network.typeText,
                    wifiLevel = network.wifiLevel, typeSuppressed = network.typeSuppressed)
            }
        } else rows
    }

    /** The entire cellular slot can be masked only when every subscribed row is represented. */
    fun replacesStatusSignal(
        rows: List<CarrierRowModel>,
        subscriptions: Collection<Int> = rows.mapNotNull { it.subId }
    ): Boolean {
        val represented = rows.filter { it.visible }
        return represented.isNotEmpty() && represented.all { it.hasSignal } &&
            represented.mapNotNull { it.subId }.toSet() == subscriptions.toSet()
    }

    /** Each vertical row gets the full width, not the host's horizontal half/text-width budget. */
    fun textWidth(availableWidth: Int, fixedWidth: Int): Int =
        (availableWidth.toLong() - fixedWidth.coerceAtLeast(0)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    fun preserveCarrierName(compactOwner: Boolean, requestedHidden: Boolean): Boolean =
        compactOwner || !requestedHidden

    fun badgeText(slot: Int, value: String): String = value.mapNotNull { char ->
        when {
            char.isWhitespace() -> ' '
            char.isISOControl() -> null
            else -> char
        }
    }.joinToString("").trim().ifBlank { (slot + 1).toString() }

    /** The badge may use at most a third of the remaining width, without displacing the name. */
    fun badgeWidth(available: Int, desired: Int, gap: Int, nameReserve: Int): Int =
        desired.coerceAtLeast(0).coerceAtMost((available / 3).coerceAtLeast(0))
            .coerceAtMost(textWidth(available, nameReserve.coerceAtLeast(0) + gap.coerceAtLeast(0)))

    fun iconHeight(resourceHeight: Int?, density: Float): Int =
        resourceHeight?.takeIf { it > 0 } ?: kotlin.math.round(20f * density).toInt().coerceAtLeast(1)

    fun typeHeight(iconHeight: Int, density: Float, fontScale: Float): Int =
        maxOf(iconHeight, kotlin.math.ceil(20f * density * fontScale).toInt()).coerceIn(1, 512)
}

/** Replacement ownership is container-local; Wi-Fi is independent from the cellular group. */
internal data class CarrierMask(val cellular: Boolean = false, val wifi: Boolean = false, val airplane: Boolean = false) {
    val active: Boolean get() = cellular || wifi || airplane
    fun slots(): Set<String> = buildSet {
        if (cellular) addAll(CELLULAR_SLOTS)
        if (wifi) addAll(WIFI_SLOTS)
        if (airplane) add("airplane")
    }

    companion object {
        val CELLULAR_SLOTS = setOf("mobile", "demo_mobile", "stacked_mobile",
            "stacked_mobile_icon", "stacked_mobile_type", "single_mobile_sim1", "single_mobile_sim2")
        val WIFI_SLOTS = setOf("wifi", "demo_wifi")
    }
}
