package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Ordered subscription bootstrap and early flow replay; callers gate the current generation. */
internal class MobileSignalSession {
    var state = MobileSignalState()
        private set
    private val pendingEvents = LinkedHashMap<Int, PendingSubEvents>()
    /** Flow values can arrive before the subscription-id StateFlow's first emission. */
    private data class PendingSubEvents(
        var signal: MobileSignalEvent.SignalModel? = null,
        var dataConnected: MobileSignalEvent.DataConnected? = null,
        var inService: MobileSignalEvent.InService? = null,
        var roaming: MobileSignalEvent.Roaming? = null,
        var nonTerrestrial: MobileSignalEvent.NonTerrestrial? = null,
        var networkType: MobileSignalEvent.NetworkType? = null,
        var wifiAvailable: MobileSignalEvent.WifiAvailable? = null,
        var visibility: MobileSignalEvent.OriginalVisibility? = null
    ) {
        fun put(event: MobileSignalEvent) {
            when (event) {
                is MobileSignalEvent.SignalModel -> signal = event
                is MobileSignalEvent.DataConnected -> dataConnected = event
                is MobileSignalEvent.InService -> inService = event
                is MobileSignalEvent.Roaming -> roaming = event
                is MobileSignalEvent.NonTerrestrial -> nonTerrestrial = event
                is MobileSignalEvent.NetworkType -> networkType = event
                is MobileSignalEvent.WifiAvailable -> wifiAvailable = event
                is MobileSignalEvent.OriginalVisibility -> visibility = event
                else -> Unit
            }
        }

        fun ordered(): List<MobileSignalEvent> = listOfNotNull(
            signal,
            dataConnected,
            inService,
            roaming,
            nonTerrestrial,
            networkType,
            wifiAvailable,
            visibility
        )
    }


    fun reduce(event: MobileSignalEvent): Boolean {
        val subId = when (event) {
            is MobileSignalEvent.SignalModel -> event.subId
            is MobileSignalEvent.DataConnected -> event.subId
            is MobileSignalEvent.InService -> event.subId
            is MobileSignalEvent.Roaming -> event.subId
            is MobileSignalEvent.NonTerrestrial -> event.subId
            is MobileSignalEvent.NetworkType -> event.subId
            is MobileSignalEvent.WifiAvailable -> event.subId
            is MobileSignalEvent.OriginalVisibility -> event.subId
            else -> null
        }
        if (subId != null && subId !in state.subscriptions) {
            pendingEvents.getOrPut(subId) { PendingSubEvents() }.put(event)
            return false
        }
        state = state.reduce(event)
        return true
    }
    fun subscriptions(ids: List<Int>) {
        state = state.reduce(MobileSignalEvent.Subscriptions(ids))
        val current = ids.toSet()
        pendingEvents.keys.toList().filter { it !in current }.forEach(pendingEvents::remove)
        ids.forEach { id -> pendingEvents.remove(id)?.ordered()?.forEach { state = state.reduce(it) } }
    }
    fun complete(adapterReady: Boolean, factoryIds: Set<Int>, bound: (Int) -> Boolean): Boolean {
        val snapshot = state
        return adapterReady && snapshot.subscriptionOrder.isNotEmpty() &&
            snapshot.subscriptionOrder.size <= MobileSignalState.MAX_RENDER_ROWS &&
            snapshot.subscriptionOrder.all { it in factoryIds } && snapshot.subscriptionOrder.all(bound)
    }
    fun clear() { pendingEvents.clear(); state = MobileSignalState() }
}
