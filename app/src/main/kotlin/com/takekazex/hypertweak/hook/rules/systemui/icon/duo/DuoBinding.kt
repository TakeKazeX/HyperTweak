package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.presentation.icon.PlanSurface
import com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable

import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.takekazex.hypertweak.util.platform.ResourceLookup
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts

/** One live battery-row binding and the view state it must restore. */
internal class DuoBinding(val battery: View, val parent: ViewGroup, val icons: View,
                      val surface: DuoSurface, val keyguardRoot: View?,
                      panelInitiallySettled: Boolean) {
    val planSurface: PlanSurface?
        get() = when (surface) {
            DuoSurface.HOME -> PlanSurface.STATUS_BAR
            DuoSurface.KEYGUARD -> if (rowPhase == AodHostPhase.DOZING)
                PlanSurface.AOD
                else PlanSurface.KEYGUARD
            DuoSurface.COLLAPSED_PROXY -> PlanSurface.CONTROL_CENTER_COLLAPSED
            DuoSurface.EXPANDED -> PlanSurface.CONTROL_CENTER_EXPANDED
            DuoSurface.UNSUPPORTED -> null
        }
    val view = DuoIconView(battery.context)
    val controlCenterClick by lazy { DuoControlCenterClick.resolve(battery.context) }
    var observer: ViewTreeObserver? = null
    var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    var hostHideBattery = false
    val privacyId = ResourceLookup.identifier(battery.resources, "mini_state_container", "id", battery.context.packageName)
    var privacyView: View? = null
    val privacyRect = RectF()
    var privacyInset = 0
    val powerMotion = DuoPowerMotion(battery.context)
    val panelMotion = DuoPanelMotion(battery.context)
    val airplaneMotion = DuoPanelMotion(battery.context, airplaneOnly = true)
    /**
     * The lockscreen airplane (carrier glyph or this ring) re-resolves its owner when this lit
     * lockscreen ring starts or stops drawing the airplane; the left reconcile it asks for is posted.
     */
    fun publishKeyguardAirplane() {
        if (surface != DuoSurface.KEYGUARD) return
        val drawn = active && view.icon.content?.airplaneMode == true
        if (drawn == keyguardAirplanePublished) return
        keyguardAirplanePublished = drawn
        IconHostPorts.left.duoOwnershipChanged()
    }

    /** The ring's airplane as a lockscreen carrier hand-over endpoint. */
    val airplaneEndpoint by lazy { DuoAirplaneEndpoint(this) }
    val statusIconsMotion = DuoStatusIconsMotion()
    val stackedSignalMotion = DuoStackedSignalMotion()
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
    val networkIds = listOf("wifi_signal", "mobile_type_single", "mobile_type", "mobile_signal").associateWith {
        ResourceLookup.identifier(battery.resources, it, "id", battery.context.packageName)
    }
    var missingSince = -1L
    var expiry: Runnable? = null
    var active = false
        set(value) {
            if (field == value) return
            field = value
            publishKeyguardAirplane()
        }
    private var keyguardAirplanePublished = false
    var failed = false
    /** The last reported reason this binding showed the native row; null while Duo is shown. */
    var nativeReason: String? = null
    var batteryGlyph: HostBatteryDrawable? = null
    var aodPercent: TextView? = null
    var aodNativeSuppressed = false
    var fullAodNativeState: FullAodNativeState? = null
    /**
     * Last keyguard phase proven for this row. The host's dozing flag decides it; while the
     * host cannot answer, the recorded phase is kept so a transient read cannot flip the row.
     */
    var rowPhase: AodHostPhase = AodHostPhase.UNKNOWN
    var batteryAnimationToAod: Boolean? = null
    var batteryAnimationDeadline = 0L
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
