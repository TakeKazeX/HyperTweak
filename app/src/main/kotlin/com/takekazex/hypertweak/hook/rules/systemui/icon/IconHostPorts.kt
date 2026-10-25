package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.view.ViewGroup

/** Cross-feature host capabilities, wired by the SystemUI composition root before attachment. */
internal class IconHostServices {
    data class Left(
        val homeSlots: () -> Set<String> = { emptySet() },
        val keyguardSlots: () -> Set<String> = { emptySet() },
        val shadeStarted: (Boolean) -> Unit = {},
        val shadeFinished: (Boolean) -> Unit = {}
    )
    data class Positions(
        val carrierMask: (Any, CarrierMask) -> Boolean = { _, _ -> false },
        val lowerRow: (ViewGroup, ViewGroup, Float, Float) -> Unit = { _, _, _, _ -> },
        val clearLowerRow: () -> Unit = {}
    )
    data class CarrierLayout(
        val owns: (ViewGroup?) -> Boolean = { false },
        val firstCenter: (ViewGroup) -> Int = { 0 }
    )
    data class Binding(
        val left: Left = Left(), val positions: Positions = Positions(),
        val carrier: CarrierLayout = CarrierLayout(),
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
    val positions get() = services.binding.positions
    val carrier get() = services.binding.carrier
    fun republish() = services.binding.republish()
    fun mobileReady() = services.binding.mobileReady()
}
