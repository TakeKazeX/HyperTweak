package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.IconSurface

/** Current network inputs and the existing visibility policy, shared by planning and previews. */
internal data class NetworkDisplayModel(
    val mobile: MobileSignalState = MobileSignalState(),
    val wifiDefault: Boolean = false,
    val wifiConnected: Boolean = false,
    val slotOf: (Int) -> Int = { -1 },
    val visibility: NetworkIconVisibility = NetworkIconVisibility()
) {
    fun hiddenCellularIds(surface: IconSurface): Set<Int> =
        visibility.hiddenCellularIds(mobile, surface, wifiDefault, slotOf)

    /** The collapsed row is the animated status-bar mirror, not the expanded destination. */
    fun hiddenCellularIds(surface: PlanSurface): Set<Int> {
        val scope = when (surface) {
            PlanSurface.CONTROL_CENTER_COLLAPSED, PlanSurface.CONTROL_CENTER_EXPANDED -> IconSurface.CONTROL_CENTER
            else -> IconSurface.STATUS_BAR
        }
        val hidden = hiddenCellularIds(scope)
        return if (surface == PlanSurface.CONTROL_CENTER_COLLAPSED) {
            hidden + visibility.simHiddenIds(mobile, IconSurface.STATUS_BAR, slotOf)
        } else hidden
    }

    fun hidesWifi(surface: IconSurface): Boolean = visibility.hidesWifi(surface, wifiConnected)

    fun nativeMask(stackedPublished: Boolean, ignoreSystemHide: Boolean = false): NativeMobileMask =
        visibility.nativeMask(mobile, slotOf, stackedPublished, ignoreSystemHide, wifiDefault)
    /** Apply replacement only where this surface's plan accepted its bars claim. */
    fun nativeMask(layout: SurfaceLayout, ignoreSystemHide: Boolean = false): NativeMobileMask {
        val replaced = mobile.subscriptionOrder.filterTo(linkedSetOf()) {
            layout.owner(SlotPart.MobileBars(it)) == Ownership.Replaced(RendererId.STACKED)
        }
        val hidden = mobile.subscriptionOrder.filterTo(linkedSetOf()) {
            layout.owner(SlotPart.MobileRow(it)) is Ownership.Hidden
        }
        val stacked = nativeMask(stackedPublished = true, ignoreSystemHide = ignoreSystemHide)
        return NativeMobileMask(hidden + stacked.hidden.intersect(replaced), stacked.typeOnly.intersect(replaced))
    }
}
