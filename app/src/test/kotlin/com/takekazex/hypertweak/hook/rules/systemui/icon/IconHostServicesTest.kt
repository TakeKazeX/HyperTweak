package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class IconHostServicesTest {
    @Test fun compositionRootPublishesCapabilitiesTogetherAndOldRetirementKeepsReplacement() {
        val services = IconHostServices(); val old = Any(); val replacement = Any()
        val effects = mutableListOf<String>()
        fun binding(label: String) = IconHostServices.Binding(
            left = IconHostServices.Left(homeSlots = { setOf(label) }, shadeStarted = { effects += "$label:start:$it" }),
            positions = IconHostServices.Positions(carrierMask = { _, _ -> effects += "$label:mask"; true }),
            republish = { effects += "$label:republish" }, mobileReady = { true }
        )
        services.bind(old, binding("old"))
        services.binding.left.shadeStarted(true)
        services.bind(replacement, binding("new")); services.retire(old)
        assertEquals(setOf("new"), services.binding.left.homeSlots())
        assertTrue(services.binding.positions.carrierMask(Any(), CarrierMask(wifi = true)))
        services.binding.republish()
        assertTrue(services.binding.mobileReady())
        assertEquals(listOf("old:start:true", "new:mask", "new:republish"), effects)
        services.retire(replacement)
        assertTrue(services.binding.left.homeSlots().isEmpty())
        assertFalse(services.binding.positions.carrierMask(Any(), CarrierMask()))
        assertFalse(services.binding.mobileReady())
        services.binding.republish()
        assertEquals(3, effects.size)
    }
}
