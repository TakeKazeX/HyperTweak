package com.takekazex.hypertweak.hook.base

import android.content.Context
import android.view.View

/** Dependency barriers, independent of feature names or installation order. */
enum class HostRecoveryPhase { WINDOWS, CONTENT, HEADERS, HEADER_DECORATIONS, SOURCES, ADAPTER_POLICY, ADAPTER, SLOT_ORDER, MANAGERS, CONTAINERS, PRESENTATION, ICONS, HANDOVER, NETWORK }

interface HostSurfaceRecovery {
    val phase: HostRecoveryPhase
    val icons: Boolean
    fun recover(scope: HostScope)
}

/** One discovery snapshot, refreshed after the native icon-group rebuild. Never retained globally. */
class HostScope(
    val context: Context,
    val component: Any?,
    var views: List<View>,
    var phase: HostRecoveryPhase = HostRecoveryPhase.WINDOWS
) {
    var headers: List<android.view.ViewGroup> = emptyList()
    var controller: Any? = null
    var managers: List<Any> = emptyList()
    var adapter: Any? = null
    var progress: Float? = null
    var visible: Boolean? = null
    var stretch: Float? = null
    var coroutineScope: Any? = null
    var wifiInteractor: Any? = null
}
