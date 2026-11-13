package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.IconSlotPolicy

/** Only native host slots and HyperTweak's independent publications participate in takeover. */
internal object IconSlotParts {
    val CELLULAR_SLOTS = setOf("mobile", "demo_mobile", "stacked_mobile", IconSlotPolicy.SLOT_STACKED_SIGNAL)
    val WIFI_SLOTS = setOf("wifi", "demo_wifi")
    val NETWORK_SLOTS = CELLULAR_SLOTS + WIFI_SLOTS
}
