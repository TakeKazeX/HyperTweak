package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalKind
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalState

/** Unknown inputs stay unknown: a disconnected collector must never produce a plausible icon. */
data class DuoBattery(val percent: Int, val charging: Boolean, val powerSave: Boolean) {
    val tone: BatteryTone
        get() = when {
            charging -> BatteryTone.CHARGING
            powerSave -> BatteryTone.POWER_SAVE
            percent <= 19 -> BatteryTone.LOW
            else -> BatteryTone.NORMAL
        }
}

enum class BatteryTone { NORMAL, CHARGING, LOW, POWER_SAVE }
enum class DuoTransport { UNKNOWN, NONE, WIFI, CELLULAR, VPN, OTHER }
enum class DuoSurface { HOME, EXPANDED, UNSUPPORTED }
enum class DuoExpandedStyle { KEEP_DUO, RESTORE_NATIVE }

data class DuoNetwork(
    val transport: DuoTransport = DuoTransport.UNKNOWN,
    val validated: Boolean = false,
    val wifiLevel: Int? = null
)

data class DuoContent(
    val battery: DuoBattery,
    val wifiLevel: Int?,
    val networkLabel: String?,
    val mobileLevel: Int,
    val noService: Boolean,
    val noInternet: Boolean,
    val airplaneMode: Boolean = false
)

object DuoPolicy {
    fun replaces(surface: DuoSurface, expandedStyle: DuoExpandedStyle): Boolean = when (surface) {
        DuoSurface.HOME -> true
        DuoSurface.EXPANDED -> expandedStyle == DuoExpandedStyle.KEEP_DUO
        DuoSurface.UNSUPPORTED -> false
    }

    /**
     * Unknown, satellite and unsupported subscription states still fall back to native. Airplane
     * mode is different: it is a complete state by itself and renders the aircraft inside Duo,
     * even when there is no active subscription to inspect.
     */
    fun content(battery: DuoBattery?, mobile: MobileSignalState, network: DuoNetwork): DuoContent? {
        if (battery == null || battery.percent !in 0..100) return null
        if (mobile.airplaneMode) {
            return DuoContent(
                battery = battery,
                wifiLevel = null,
                networkLabel = null,
                mobileLevel = 0,
                noService = false,
                noInternet = false,
                airplaneMode = true
            )
        }
        val sub = mobile.subscriptions[mobile.activeDataSubId] ?: return null
        if (mobile.activeDataSubId !in mobile.subscriptionOrder || !sub.supportsReplacement) return null
        if (sub.inService == null) return null
        // The default network can be a VPN, so transport alone cannot tell whether the
        // status-bar Wi-Fi glyph should be shown. A non-null host WifiNetworkModel level is the
        // authoritative Wi-Fi signal here; it also keeps Duo stable while a VPN is active.
        val wifiLevel = network.wifiLevel?.takeIf { it in 0..4 }
        val wifi = wifiLevel != null
        val noService = sub.kind == MobileSignalKind.NO_SERVICE
        val cellular = !wifi && when (network.transport) {
            DuoTransport.CELLULAR -> true
            // A VPN hides the underlying transport from the default-network callback. Use the
            // host mobile reducer only as the fallback in that specific case.
            DuoTransport.VPN -> sub.dataConnected == true
            else -> false
        }
        val label = if (cellular) {
            // Only render bounded, host-provided labels; don't invent 5G from radio strength.
            sub.networkType?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8 && it.none(Char::isISOControl) }
                ?: return null
        } else null
        // NO_SERVICE is a supported model: when there is no default data network, keep Duo and
        // let the drawable render its no-service treatment instead of falling back to native.
        if (!wifi && label == null && !noService) return null
        val noInternet = !network.validated && (wifi || cellular)
        return DuoContent(
            battery, wifiLevel, label, sub.normalizedLevel,
            noService, noInternet
        )
    }
}
