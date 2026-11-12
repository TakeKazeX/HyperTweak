package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierMask
import android.view.ViewGroup
import android.view.View
import android.content.Context

/** Cross-feature host capabilities, wired by the SystemUI composition root before attachment. */
internal class IconHostServices {
    data class Left(
        val homeSlots: () -> Set<String> = { emptySet() },
        val keyguardSlots: () -> Set<String> = { emptySet() },
        val shadeStarted: (Boolean) -> Unit = {},
        val shadeFinished: (Boolean) -> Unit = {},
        val visibilityChanged: () -> Unit = {},
        /** Duo took or released a slot the left container may also own. */
        val duoOwnershipChanged: () -> Unit = {}
    )
    data class Keyguard(
        val showing: () -> Boolean? = { null },
        val suppressed: () -> Boolean = { false }
    )
    data class Positions(
        val carrierMask: (Any, CarrierMask) -> Boolean = { _, _ -> false },
        val lowerRow: (ViewGroup, ViewGroup, Float, Float) -> Unit = { _, _, _, _ -> },
        val clearLowerRow: () -> Unit = {}
    )
    data class CarrierLayout(
        val owns: (ViewGroup?) -> Boolean = { false },
        val firstCenter: (ViewGroup) -> Int = { 0 },
        val ownsRow: (ViewGroup?) -> Boolean = { false },
        val geometry: (ViewGroup) -> TwoRowIconLayout.Geometry? = { null }
    )
    data class Duo(
        val releaseStatusIcons: (Set<String>) -> Unit = {},
        /** The airplane Duo draws in the lockscreen ring under this window root, if any. */
        val keyguardAirplane: (View) -> GlyphEndpoint? = { null },
        /** Why [keyguardAirplane] found no ring under this root, for the location log. */
        val keyguardAirplaneState: (View) -> String = { "unbound" }
    )
    data class Header(
        val supportsCompactLayout: (View) -> Boolean = { false },
        val ensureCompactCarrierVisible: (ViewGroup) -> Unit = {},
        val updateCarrierLayout: (ViewGroup, Boolean) -> Unit = { _, _ -> },
        val releaseCarrier: (ViewGroup) -> Unit = {},
        val twoLineConfigured: () -> Boolean = { false },
        val secondRowStatusIconsEnabled: (Context) -> Boolean = { false }
    )
    data class Binding(
        val left: Left = Left(), val positions: Positions = Positions(),
        val carrier: CarrierLayout = CarrierLayout(),
        val duo: Duo = Duo(), val header: Header = Header(),
        val keyguard: Keyguard = Keyguard(),
        val republish: () -> Unit = {}, val mobileReady: () -> Boolean = { false }
    )
    private data class Owned(val owner: Any?, val binding: Binding)
    @Volatile private var current = Owned(null, Binding())
    val binding get() = current.binding
    @Synchronized fun bind(owner: Any, binding: Binding) { current = Owned(owner, binding) }
    @Synchronized fun retire(owner: Any) {
        if (current.owner === owner) current = Owned(null, Binding())
    }
}

internal object IconHostPorts {
    val services = IconHostServices()
    val left get() = services.binding.left
    val keyguard get() = services.binding.keyguard
    val positions get() = services.binding.positions
    val carrier get() = services.binding.carrier
    val duo get() = services.binding.duo
    val header get() = services.binding.header
    fun republish() = services.binding.republish()
    fun mobileReady() = services.binding.mobileReady()
}
