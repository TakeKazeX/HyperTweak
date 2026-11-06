package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Association alone does not make Wi-Fi the route used by the system (including VPN). */
internal object WifiRoutePolicy {
    fun hide(active: Boolean, isDefault: Boolean?, hideConnected: Boolean): Boolean =
        active && (isDefault == false || hideConnected)
}
