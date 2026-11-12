package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.presentation.icon.duo.DuoNetwork
import com.takekazex.hypertweak.presentation.icon.duo.DuoTransport

enum class NetworkTransport { UNKNOWN, NONE, WIFI, CELLULAR, VPN, OTHER }

/** Host observations; null means the corresponding Wi-Fi input is not available. */
data class NetworkState(
    val transport: NetworkTransport = NetworkTransport.UNKNOWN,
    val validated: Boolean = false,
    val wifiLevel: Int? = null,
    val wifiDefault: Boolean? = null,
    val wifiStandard: Int? = null,
    val wifiActivity: Int? = null,
)

fun NetworkState.toDuoNetwork(): DuoNetwork = DuoNetwork(when (transport) {
    NetworkTransport.UNKNOWN -> DuoTransport.UNKNOWN
    NetworkTransport.NONE -> DuoTransport.NONE
    NetworkTransport.WIFI -> DuoTransport.WIFI
    NetworkTransport.CELLULAR -> DuoTransport.CELLULAR
    NetworkTransport.VPN -> DuoTransport.VPN
    NetworkTransport.OTHER -> DuoTransport.OTHER
}, validated, wifiLevel, wifiDefault)
