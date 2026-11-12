package com.takekazex.hypertweak.presentation.icon

/** Canonical host aliases; all claims and motion matching use this vocabulary. */
internal object IconSlotParts {
    val CELLULAR_SLOTS = setOf("mobile", "demo_mobile", "stacked_mobile", "stacked_mobile_icon")
    val WIFI_SLOTS = setOf("wifi", "demo_wifi")
    val NETWORK_SLOTS = CELLULAR_SLOTS + WIFI_SLOTS
}
