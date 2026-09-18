package com.takekazex.hypertweak.hook.rules.systemui.icon

/**
 * One row of the two-line control-center carrier block.
 *
 * The row keeps the cellular strength and the trailing indicators independent on purpose: the
 * Wi-Fi glyph is additive on the first row (the host shows it beside the cellular type in the
 * status bar too), while the type text belongs to the SIM that actually carries data.
 */
data class CarrierRowModel(
    val slot: Int,
    val subId: Int? = null,
    /** `renderLevel` of this SIM (`-1` = no service), or null when no honest level exists. */
    val signalLevel: Int? = null,
    /** Network type text ("5G", "4G+…") for this row, or null when it should not be drawn. */
    val typeText: String? = null,
    /** Wi-Fi level 0..4; only the first row ever carries it. */
    val wifiLevel: Int? = null,
    /**
     * Wi-Fi carries the data connection and the type is not wanted. The glyph is still rendered so
     * its box keeps the row's width budget — the name must not grow and push the trailing glyphs
     * sideways; only the alpha fades.
     */
    val typeSuppressed: Boolean = false
) {
    /** A row is drawn only when a subscription actually owns its slot. */
    val visible: Boolean get() = subId != null

    val hasSignal: Boolean get() = visible && signalLevel != null
    val hasType: Boolean get() = visible && !typeText.isNullOrEmpty()
    val hasWifi: Boolean get() = visible && wifiLevel != null
}

/** User choices that change only the row mapping, never the host data. */
data class CarrierBlockConfig(
    /** Switch 4: draw the network type of the SIM that is not the default data line too. */
    val showNonDataType: Boolean = false,
    /** 连接 WiFi 时依旧显示蜂窝类型: keep the type readable while Wi-Fi carries the data. */
    val keepTypeOnWifi: Boolean = false
)

/**
 * Pure mapping from the shared mobile reducer to the two carrier rows.
 *
 * `slotOf` resolves a subscription id to its physical SIM slot; it is injected so this policy keeps
 * working (and stays unit-testable) without `SubscriptionManager`. Rows are always emitted in
 * physical slot order — 卡一 above 卡二 — never in subscription-flow order, because the host
 * reorders `subscriptionOrder` when the user swaps the default data SIM.
 */
object CarrierBlockPolicy {
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
            ?.takeIf { !state.airplaneMode && it.inService != false }
            ?.networkType
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { subId == state.activeDataSubId || config.showNonDataType }
        // Wi-Fi is an extra indicator on 卡一 only; a missing level must not invent bars.
        val rowWifi = wifiLevel
            ?.coerceIn(0, 4)
            ?.takeIf { slot == 0 && state.wifiConnected }
        CarrierRowModel(
            slot = slot,
            subId = subId,
            signalLevel = signalLevel,
            typeText = typeText,
            wifiLevel = rowWifi,
            // Matches the host's own single type rule (`... && !wifiAvailable`) and the stacked
            // slot's `hideWhenWifiAvailable`; the row keeps the glyph's width either way.
            typeSuppressed = typeText != null && state.wifiConnected && !config.keepTypeOnWifi
        )
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
internal data class CarrierMask(val cellular: Boolean = false, val wifi: Boolean = false) {
    val active: Boolean get() = cellular || wifi
    fun slots(): Set<String> = buildSet {
        if (cellular) addAll(CELLULAR_SLOTS)
        if (wifi) addAll(WIFI_SLOTS)
    }

    companion object {
        val CELLULAR_SLOTS = setOf("mobile", "demo_mobile", "stacked_mobile",
            "stacked_mobile_icon", "stacked_mobile_type", "single_mobile_sim1", "single_mobile_sim2")
        val WIFI_SLOTS = setOf("wifi", "demo_wifi")
    }
}
