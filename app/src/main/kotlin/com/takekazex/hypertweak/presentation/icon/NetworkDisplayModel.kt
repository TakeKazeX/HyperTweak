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

    fun hidesWifi(surface: IconSurface): Boolean = visibility.hidesWifi(surface, wifiConnected)

    fun nativeMask(stackedPublished: Boolean, ignoreSystemHide: Boolean = false): NativeMobileMask =
        visibility.nativeMask(mobile, slotOf, stackedPublished, ignoreSystemHide, wifiDefault)
}
