package com.takekazex.hypertweak.hook.rules.systemui.icon

import kotlin.math.roundToInt

/**
 * The model kinds that the signal replacement understands.
 *
 * [EMPTY] is the state of the whole subscription list. The other values describe one
 * subscription. Keeping satellite and unknown values explicit is important: a missing model is
 * not a zero-bar cellular model and must never cause the native icon to be hidden.
 */
enum class MobileSignalKind {
    EMPTY,
    UNKNOWN,
    NO_SERVICE,
    CELLULAR,
    SATELLITE
}

/** A host signal model reduced to values that do not cross the SystemUI class-loader boundary. */
data class MobileSignalModel(
    val kind: MobileSignalKind,
    val level: Int = 0,
    val numberOfLevels: Int = 5,
    val showExclamationMark: Boolean = false,
    val carrierNetworkChange: Boolean = false
) {
    companion object {
        fun cellular(
            level: Int,
            numberOfLevels: Int,
            showExclamationMark: Boolean = false,
            carrierNetworkChange: Boolean = false
        ) = MobileSignalModel(
            kind = MobileSignalKind.CELLULAR,
            level = level,
            numberOfLevels = numberOfLevels,
            showExclamationMark = showExclamationMark,
            carrierNetworkChange = carrierNetworkChange
        )

        fun satellite(level: Int = 0, numberOfLevels: Int = 5) = MobileSignalModel(
            kind = MobileSignalKind.SATELLITE,
            level = level,
            numberOfLevels = numberOfLevels
        )

        fun unknown() = MobileSignalModel(MobileSignalKind.UNKNOWN)
    }
}

/**
 * One subscription's immutable, reduced state. [modelKind] is kept separately from [kind] so a
 * `Cellular` model followed by `isInService=false` becomes [MobileSignalKind.NO_SERVICE] without
 * confusing a missing model with no service.
 */
data class MobileSubscriptionState(
    val subId: Int,
    val modelKind: MobileSignalKind = MobileSignalKind.UNKNOWN,
    val level: Int = 0,
    val numberOfLevels: Int = 5,
    val showExclamationMark: Boolean = false,
    val carrierNetworkChange: Boolean = false,
    val dataConnected: Boolean? = null,
    val inService: Boolean? = null,
    val roaming: Boolean? = null,
    val nonTerrestrial: Boolean? = null,
    val networkType: String? = null,
    val wifiAvailable: Boolean? = null,
    val originalVisible: Boolean = true,
    val originalDetailVisible: Boolean = true
) {
    val kind: MobileSignalKind
        get() = when {
            modelKind == MobileSignalKind.CELLULAR && nonTerrestrial == true -> MobileSignalKind.SATELLITE
            modelKind == MobileSignalKind.CELLULAR && inService == false -> MobileSignalKind.NO_SERVICE
            else -> modelKind
        }

    /** Normalizes host levels such as 0..4 or 0..5 to the renderer's fixed 0..4 range. */
    val normalizedLevel: Int
        get() = normalizeLevel(level, numberOfLevels)

    /** The renderer's error branch is selected for a known cellular model with no service. */
    val renderLevel: Int
        get() = if (kind == MobileSignalKind.NO_SERVICE) -1 else normalizedLevel

    val supportsReplacement: Boolean
        get() = kind == MobileSignalKind.CELLULAR || kind == MobileSignalKind.NO_SERVICE

    companion object {
        /**
         * [numberOfLevels] is the number of entries, so a five-level model has a maximum index of
         * four. This gives the required 4/5 -> 4 and 5/6 -> 4 mappings while retaining sensible
         * rounding for models with another number of entries.
         */
        fun normalizeLevel(level: Int, numberOfLevels: Int): Int {
            val maxIndex = (numberOfLevels - 1).coerceAtLeast(1)
            val clamped = level.coerceIn(0, maxIndex)
            return (clamped * 4f / maxIndex).roundToInt().coerceIn(0, 4)
        }
    }
}

/** Events accepted by [MobileSignalState.reduce]. */
sealed interface MobileSignalEvent {
    data class Subscriptions(val subIds: List<Int>) : MobileSignalEvent
    data class SignalModel(val subId: Int, val model: MobileSignalModel) : MobileSignalEvent
    data class DataConnected(val subId: Int, val value: Boolean) : MobileSignalEvent
    data class InService(val subId: Int, val value: Boolean) : MobileSignalEvent
    data class Roaming(val subId: Int, val value: Boolean) : MobileSignalEvent
    data class NonTerrestrial(val subId: Int, val value: Boolean) : MobileSignalEvent
    data class NetworkType(val subId: Int, val value: String?) : MobileSignalEvent
    data class WifiAvailable(val subId: Int, val value: Boolean) : MobileSignalEvent
    data class OriginalVisibility(
        val subId: Int,
        val visible: Boolean,
        val detailVisible: Boolean
    ) : MobileSignalEvent

    data class ActiveDataSubId(val subId: Int) : MobileSignalEvent
    data class AirplaneMode(val enabled: Boolean) : MobileSignalEvent
}

/**
 * Pure reducer for all state used by the custom cellular signal slots.
 *
 * The order comes from SystemUI's subscription flow, never from the active data subscription.
 * Therefore changing the data SIM changes only [activeDataSubId]; it cannot silently swap the two
 * rendered rows.
 */
data class MobileSignalState(
    val subscriptionOrder: List<Int> = emptyList(),
    val subscriptions: Map<Int, MobileSubscriptionState> = emptyMap(),
    val activeDataSubId: Int = INVALID_SUB_ID,
    val airplaneMode: Boolean = false
) {
    val kind: MobileSignalKind
        get() = when {
            subscriptionOrder.isEmpty() -> MobileSignalKind.EMPTY
            rows.size != subscriptionOrder.size -> MobileSignalKind.UNKNOWN
            rows.any { it.kind == MobileSignalKind.UNKNOWN } -> MobileSignalKind.UNKNOWN
            rows.any { it.kind == MobileSignalKind.SATELLITE } -> MobileSignalKind.SATELLITE
            rows.any { it.kind == MobileSignalKind.NO_SERVICE } -> MobileSignalKind.NO_SERVICE
            else -> MobileSignalKind.CELLULAR
        }

    val rows: List<MobileSubscriptionState>
        get() = subscriptionOrder.mapNotNull(subscriptions::get)

    /**
     * A replacement may be rendered only when every row is a known cellular model.
     *
     * The icon-tuner "ignore system hide" option intentionally bypasses only the host
     * visibility pair. It does not bypass airplane mode, incomplete rows, satellite, or
     * unknown-model safeguards.
     */
    fun canRenderReplacement(ignoreSystemHide: Boolean = false): Boolean = !airplaneMode &&
        rows.size == subscriptionOrder.size && rows.isNotEmpty() &&
        rows.size <= MAX_RENDER_ROWS && rows.all {
            it.supportsReplacement && (ignoreSystemHide || it.originalVisible)
        }

    /** Native subscriptions hidden by a successfully published stacked/single replacement. */
    fun replacementMask(
        published: Boolean,
        ignoreSystemHide: Boolean = false
    ): Set<Int> = if (published && canRenderReplacement(ignoreSystemHide)) {
        rows.mapTo(LinkedHashSet()) { it.subId }
    } else {
        emptySet()
    }

    /** Native subscriptions hidden by the legacy "hide non-default SIM" option. */
    fun nonDefaultMask(published: Boolean): Set<Int> = if (
        published && !airplaneMode && subscriptionOrder.size > 1 &&
        activeDataSubId in subscriptions
    ) {
        subscriptionOrder.filterTo(LinkedHashSet()) { it != activeDataSubId }
    } else {
        emptySet()
    }

    fun reduce(event: MobileSignalEvent): MobileSignalState = when (event) {
        is MobileSignalEvent.Subscriptions -> reduceSubscriptions(event.subIds)
        is MobileSignalEvent.SignalModel -> updateSubscription(event.subId) {
            it.copy(
                modelKind = event.model.kind,
                level = event.model.level,
                numberOfLevels = event.model.numberOfLevels,
                showExclamationMark = event.model.showExclamationMark,
                carrierNetworkChange = event.model.carrierNetworkChange
            )
        }
        is MobileSignalEvent.DataConnected -> updateSubscription(event.subId) {
            it.copy(dataConnected = event.value)
        }
        is MobileSignalEvent.InService -> updateSubscription(event.subId) {
            it.copy(inService = event.value)
        }
        is MobileSignalEvent.Roaming -> updateSubscription(event.subId) {
            it.copy(roaming = event.value)
        }
        is MobileSignalEvent.NonTerrestrial -> updateSubscription(event.subId) {
            it.copy(nonTerrestrial = event.value)
        }
        is MobileSignalEvent.NetworkType -> updateSubscription(event.subId) {
            it.copy(networkType = event.value?.trim()?.takeIf(String::isNotEmpty))
        }
        is MobileSignalEvent.WifiAvailable -> updateSubscription(event.subId) {
            it.copy(wifiAvailable = event.value)
        }
        is MobileSignalEvent.OriginalVisibility -> updateSubscription(event.subId) {
            it.copy(
                originalVisible = event.visible,
                originalDetailVisible = event.detailVisible
            )
        }
        is MobileSignalEvent.ActiveDataSubId -> copy(activeDataSubId = event.subId)
        is MobileSignalEvent.AirplaneMode -> copy(airplaneMode = event.enabled)
    }

    private fun reduceSubscriptions(ids: List<Int>): MobileSignalState {
        val order = ids.asSequence()
            .filter { it != INVALID_SUB_ID }
            .distinct()
            .toList()
        val next = LinkedHashMap<Int, MobileSubscriptionState>(order.size)
        order.forEach { id ->
            next[id] = subscriptions[id] ?: MobileSubscriptionState(id)
        }
        return copy(subscriptionOrder = order, subscriptions = next)
    }

    private fun updateSubscription(
        subId: Int,
        transform: (MobileSubscriptionState) -> MobileSubscriptionState
    ): MobileSignalState {
        val old = subscriptions[subId] ?: return this
        val next = LinkedHashMap(subscriptions)
        next[subId] = transform(old)
        return copy(subscriptions = next)
    }

    companion object {
        const val INVALID_SUB_ID = -1
        const val MAX_RENDER_ROWS = 2
    }
}
