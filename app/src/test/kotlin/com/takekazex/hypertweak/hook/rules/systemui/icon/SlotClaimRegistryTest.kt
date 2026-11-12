package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.*
import com.takekazex.hypertweak.settings.contract.*
import org.junit.Assert.*
import org.junit.Test

class SlotClaimRegistryTest {
    private fun config(style: SignalStyle, mode: IconSlotMode) = IconConfigDecoder.decode { key ->
        when (key) {
            "icon_signal_style" -> style.ordinal
            "icon_tuner_slot_wifi" -> mode.value
            else -> null
        }
    }

    @Test fun duoClaimsFollowEverySurfaceAndSlotRangeIncludingCachedSurfaceChanges() {
        for (mode in IconSlotMode.entries) {
            val config = config(SignalStyle.DUO, mode)
            val registry = SlotClaimRegistry { config }
            val container = Any()
            registry.set(container, RendererId.DUO, setOf("wifi"))
            for (surface in PlanSurface.entries + PlanSurface.entries.reversed()) {
                val cc = surface == PlanSurface.CONTROL_CENTER_COLLAPSED || surface == PlanSurface.CONTROL_CENTER_EXPANDED
                val hidden = mode == IconSlotMode.HIDE_EVERYWHERE ||
                    mode == IconSlotMode.STATUS_BAR_ONLY && cc || mode == IconSlotMode.CONTROL_CENTER_ONLY && !cc
                assertEquals("$mode/$surface", !hidden, "wifi" in registry.slots(container, surface))
            }
        }
    }

    @Test fun normalStyleStillAllowsIndependentAodAndKeyguardDuoAndReleasesIt() {
        var config = config(SignalStyle.NORMAL, IconSlotMode.STATUS_BAR_ONLY)
        val registry = SlotClaimRegistry { config }; val container = Any()
        registry.set(container, RendererId.DUO, setOf("wifi"))
        assertTrue(registry.slots(container, PlanSurface.STATUS_BAR).isEmpty())
        assertEquals(listOf("wifi"), registry.slots(container, PlanSurface.AOD))
        assertEquals(listOf("wifi"), registry.slots(container, PlanSurface.KEYGUARD))
        config = config(SignalStyle.DUO, IconSlotMode.CONTROL_CENTER_ONLY)
        assertTrue(registry.slots(container, PlanSurface.AOD).isEmpty())
        assertEquals(listOf("wifi"), registry.slots(container, PlanSurface.CONTROL_CENTER_EXPANDED))
        registry.set(container, RendererId.DUO, emptySet())
        assertTrue(registry.slots(container, PlanSurface.CONTROL_CENTER_EXPANDED).isEmpty())
    }
}
