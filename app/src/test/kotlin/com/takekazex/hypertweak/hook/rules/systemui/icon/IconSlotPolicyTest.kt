package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconSlotPolicyTest {
    @Test
    fun fiveModesAreExplicitForEachKnownSurface() {
        val base = listOf("wifi")
        val expected = mapOf(
            IconSurface.STATUS_BAR to listOf(
                listOf("wifi"),
                emptyList(),
                emptyList(),
                listOf("wifi"),
                listOf("wifi")
            ),
            IconSurface.CONTROL_CENTER to listOf(
                listOf("wifi"),
                emptyList(),
                listOf("wifi"),
                emptyList(),
                listOf("wifi")
            ),
            IconSurface.UNKNOWN to listOf(
                listOf("wifi"),
                emptyList(),
                listOf("wifi"),
                listOf("wifi"),
                listOf("wifi")
            )
        )

        IconSurface.entries.forEach { surface ->
            IconSlotMode.entries.forEach { mode ->
                val config = IconSlotPolicyConfig(slotModes = mapOf("wifi" to mode.value))
                assertEquals(
                    "surface=$surface mode=$mode",
                    expected.getValue(surface)[mode.value],
                    IconSlotPolicy.blockedFor(surface, base, config)
                )
            }
        }
    }

    @Test
    fun aliasesPreferIndependentSlotAndExtraHiddenWins() {
        assertEquals(
            IconSlotMode.HIDE_EVERYWHERE,
            IconSlotPolicy.modeFor("demo_wifi", mapOf("wifi" to 4))
        )
        assertEquals(
            IconSlotMode.SHOW_EVERYWHERE,
            IconSlotPolicy.modeFor("demo_wifi", mapOf("wifi" to 4, "demo_wifi" to 1))
        )
        assertEquals(
            IconSlotMode.SHOW_EVERYWHERE,
            IconSlotPolicy.modeFor("gps", mapOf("location" to 1))
        )
        assertEquals(
            IconSlotMode.HIDE_EVERYWHERE,
            IconSlotPolicy.modeFor("wifi", mapOf("wifi" to 1), setOf("wifi"))
        )
    }

    @Test
    fun malformedCustomOrderIsSkippedAndUnknownHostSlotsSurvive() {
        val base = listOf("future_slot", "wifi", "mobile", "wifi")
        val entries = linkedSetOf(
            "broken",
            "not-a-number:wifi",
            "3:",
            "2:wifi",
            "0:mobile",
            "1:future_slot"
        )
        val result = IconSlotPolicy.normalizeOrder(
            base,
            IconSlotPolicyConfig(
                position = IconSlotPolicyConfig.POSITION_CUSTOM,
                customOrderEntries = entries
            )
        )
        assertEquals(listOf("mobile", "future_slot", "wifi"), result)
        assertTrue(result.contains("future_slot"))
    }

    @Test
    fun wifiPositionAndEnabledSignalModulesAreStableAndDeduplicated() {
        val result = IconSlotPolicy.normalizeOrder(
            listOf("mobile", "future_slot", "wifi", "demo_wifi"),
            IconSlotPolicyConfig(
                position = IconSlotPolicyConfig.POSITION_WIFI_BEFORE_MOBILE,
                enabledModuleSlots = IconSlotPolicy.SIGNAL_SLOTS.toSet()
            )
        )
        assertEquals(
            listOf(
                "wifi",
                "demo_wifi",
                "stacked_mobile_icon",
                "stacked_mobile_type",
                "single_mobile_sim1",
                "single_mobile_sim2",
                "mobile",
                "future_slot"
            ),
            result
        )
    }

    @Test
    fun hiddenReorderIsStableAndKeepsSignalGroupInPlacePartition() {
        val slots = listOf("visible_a", "hidden_a", "stacked_mobile_icon", "hidden_b", "stacked_mobile_type")
        assertEquals(
            listOf("hidden_a", "hidden_b", "visible_a", "stacked_mobile_icon", "stacked_mobile_type"),
            IconSlotPolicy.reorderHidden(
                slots,
                hiddenSlots = setOf("hidden_a", "hidden_b", "stacked_mobile_icon"),
                signalSlots = setOf("stacked_mobile_icon", "stacked_mobile_type")
            )
        )
    }

    @Test
    fun leftPlacementContributesToHiddenOrderWithoutChangingUnknownSurface() {
        val config = IconSlotPolicyConfig(
            reorderHidden = true,
            leftSlots = setOf("zen"),
            slotModes = mapOf("wifi" to IconSlotMode.STATUS_BAR_ONLY.value)
        )
        assertEquals(listOf("wifi"), IconSlotPolicy.blockedFor(IconSurface.UNKNOWN, listOf("wifi"), config))
        assertEquals(listOf("zen"), IconSlotPolicy.blockedFor(IconSurface.STATUS_BAR, listOf("zen"), config))
        assertEquals(
            setOf("zen"),
            IconSlotPolicy.hiddenSlots(config).intersect(setOf("zen"))
        )
    }
}
