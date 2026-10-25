package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable

import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView

/** One live battery-row binding and the view state it must restore. */
internal class DuoBinding(val battery: View, val parent: ViewGroup, val icons: View,
                      val surface: DuoSurface, val keyguardRoot: View?,
                      panelInitiallySettled: Boolean) {
    val view = DuoIconView(battery.context)
    var observer: ViewTreeObserver? = null
    var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    var hostHideBattery = false
    val privacyId = battery.resources.getIdentifier("mini_state_container", "id", battery.context.packageName)
    var privacyView: View? = null
    val privacyRect = RectF()
    var privacyInset = 0
    val panelMotion = DuoPanelMotion(battery.context)
    val airplaneMotion = DuoPanelMotion(battery.context, airplaneOnly = true)
    val signalMotions = HashMap<Int, SignalRowMotion>()
    val signalTargets = HashMap<Int, View>()
    val signalSourceBounds = RectF()
    val signalPictureCrop = RectF()
    var carrierTarget: View? = null
    var proxyRoot: View? = null
    val proxyX = DuoOwnedTranslation()
    val proxyY = DuoOwnedTranslation()
    var networkMotionProgress = 0f
    val panelArrival = DuoPanelArrival(panelInitiallySettled)
    var networkMotionSettle: Runnable? = null
    val networkIds = listOf("wifi_signal", "mobile_type", "mobile_signal").associateWith {
        battery.resources.getIdentifier(it, "id", battery.context.packageName)
    }
    var missingSince = -1L
    var expiry: Runnable? = null
    var active = false
    var failed = false
    var batteryGlyph: HostBatteryDrawable? = null
    var aodPercent: TextView? = null
    var aodNativeSuppressed = false
    var fullAodNativeState: FullAodNativeState? = null
    /**
     * Last keyguard phase proven for this row. The host's dozing flag decides it; while the
     * host cannot answer, the recorded phase is kept so a transient read cannot flip the row.
     */
    var rowPhase: AodHostPhase = AodHostPhase.UNKNOWN
    var dotsInMotion = false
    var reconciling = false
    // Preserve whether the host/user already ignored the native airplane slot before Duo.
    // Duo may add that slot while active, but must never unhide something the host chose to hide.
    var hostIgnoredAirplane: Boolean? = null
    var airplaneMasked = false
    var wifiGlyph: NativeWifiDrawable? = null
    var wifiArtworkRevision = -1
}

internal data class FullAodNativeState(
    val iconContainer: View,
    val percentContainer: View,
    val chargingView: View?,
    val iconVisibility: Int,
    val percentVisibility: Int,
    val chargingVisibility: Int?
)
