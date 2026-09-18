package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconSlotPolicyTest {
    @Test fun releasingOneNetworkOwnerKeepsTheOtherOwnerAndUserBlocks() {
        val masks = IconMaskOwners()
        val container = Any()
        val otherContainer = Any()
        val host = listOf("alarm_clock", "wifi")
        val duo = IconMaskOwners.Owner.DUO
        val carrier = IconMaskOwners.Owner.CARRIER
        masks.set(container, duo, setOf("mobile", "wifi"))
        masks.set(container, carrier, CarrierMask(cellular = true, wifi = true).slots())
        masks.set(container, duo, emptySet())
        assertTrue("mobile" in masks.slots(container))
        assertTrue("wifi" in masks.slots(container))
        assertTrue(masks.slots(otherContainer).isEmpty())
        masks.set(container, carrier, CarrierMask(cellular = true).slots())
        assertTrue("wifi" !in masks.slots(container))
        assertTrue("wifi" in masks.merged(container, host))
        masks.set(container, duo, setOf("mobile", "wifi"))
        masks.set(container, carrier, emptySet())
        assertEquals(setOf("mobile", "wifi"), masks.slots(container).toSet())
        masks.set(container, duo, emptySet())
        assertEquals(host, masks.merged(container, host))
    }

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

    @Test
    fun leftAlarmToggleUsesAlarmClockSlotNotClockSlot() {
        assertEquals(
            listOf("alarm_clock"),
            IconTunerOptions.slotsForLeftPreference(Preferences.KEY_ICON_LEFT_ALARM_CLOCK)
        )
    }

    /**
     * The order page shows [IconSlotPolicy.displayOrder] and stores
     * [IconSlotPolicy.orderEntries]; a drag must survive that round trip, and an entry the catalog
     * cannot draw must stay in the stored set instead of being dropped by the next write.
     */
    @Test
    fun displayedOrderRoundTripsThroughTheStoredWireFormat() {
        val catalog = listOf("mobile", "wifi", "hotspot", "nfc")
        assertEquals(catalog, IconSlotPolicy.displayOrder(emptySet(), catalog))
        assertEquals(
            listOf("wifi", "mobile", "hotspot", "nfc"),
            IconSlotPolicy.displayOrder(setOf("0:wifi"), catalog)
        )

        val stored = setOf("0:wifi", "1:mute")
        assertEquals(
            listOf("wifi", "mobile", "hotspot", "nfc"),
            IconSlotPolicy.displayOrder(stored, catalog)
        )
        val dragging = listOf("nfc", "wifi", "mobile", "hotspot")
        val written = IconSlotPolicy.orderEntries(dragging + listOf("mute"))
        assertEquals(
            dragging + listOf("mute"),
            IconSlotPolicy.parseLegacyOrder(written).map { it.slot }
        )
        assertEquals(dragging, IconSlotPolicy.displayOrder(written, catalog))
    }

    @Test
    fun hiddenSlotListIsParsedWithEveryAcceptedSeparator() {
        assertEquals(
            listOf("wifi", "nfc"),
            IconSlotPolicy.parseSlotList(" wifi ,nfc,\uFF0C ")
        )
        assertEquals(emptyList<String>(), IconSlotPolicy.parseSlotList(""))
    }

    /**
     * `icon_ext_blocked` is HIDE_EVERYWHERE, so folding it must not outrank the per-slot dropdown:
     * the settings page offers that same mode, and a legacy entry that overrode it made the slot
     * impossible to unhide.
     */
    @Test
    fun legacyExtraHiddenFoldsInAsADefaultNotAnOverride() {
        val folded = IconSlotPolicy.foldExtraHiddenIntoModes(
            mapOf("wifi" to IconSlotMode.SHOW_EVERYWHERE.value),
            listOf("wifi", "zen")
        )
        assertEquals(IconSlotMode.SHOW_EVERYWHERE.value, folded["wifi"])
        assertEquals(IconSlotMode.HIDE_EVERYWHERE.value, folded["zen"])

        val config = IconSlotPolicyConfig(
            slotModes = folded,
            extraHiddenSlots = emptySet()
        )
        assertEquals(IconSlotMode.HIDE_EVERYWHERE, IconSlotPolicy.modeFor("zen", config.slotModes))
        assertEquals(
            listOf("zen"),
            IconSlotPolicy.blockedFor(IconSurface.STATUS_BAR, listOf("wifi", "zen"), config)
        )
    }

    @Test
    fun foldingAnEmptyOrBlankExtraHiddenListChangesNothing() {
        val modes = mapOf("wifi" to IconSlotMode.STATUS_BAR_ONLY.value)
        assertEquals(modes, IconSlotPolicy.foldExtraHiddenIntoModes(modes, emptyList()))
        assertEquals(modes, IconSlotPolicy.foldExtraHiddenIntoModes(modes, listOf(" ", "")))
    }

    /**
     * Left placement takes icons out of the home row only. The control center draws its own rows in
     * its own layout, where the icons belong on the right — that is what makes them end up on the
     * right once the shade is open — and the keyguard is only affected when its own left container
     * exists (i.e. 主屏和锁屏).
     */
    @Test
    fun leftOwnedSlotsAreTakenFromTheRowThatDrawsThemOnly() {
        val home = setOf("zen", "volume")
        val keyguard = setOf("nfc")
        assertEquals(home, IconSlotPolicy.ownedSlotsFor("HOME", home, keyguard))
        assertEquals(keyguard, IconSlotPolicy.ownedSlotsFor("KEYGUARD", home, keyguard))
        // The control center's own rows keep drawing them.
        assertEquals(emptySet<String>(), IconSlotPolicy.ownedSlotsFor("QS", home, keyguard))
        assertEquals(emptySet<String>(), IconSlotPolicy.ownedSlotsFor("QS_FAKE", home, keyguard))
        assertEquals(emptySet<String>(), IconSlotPolicy.ownedSlotsFor(null, home, keyguard))
        assertEquals(
            emptySet<String>(),
            IconSlotPolicy.ownedSlotsFor("UNKNOWN_FUTURE_LOCATION", home, keyguard)
        )
    }

    @Test
    fun ownedSlotsAreFoldedIntoABlockListWithoutDuplicatingIt() {
        val blocked = listOf("bluetooth", "nfc")
        assertEquals(blocked, IconSlotPolicy.withOwnedSlots(blocked, emptyList()))
        assertEquals(blocked, IconSlotPolicy.withOwnedSlots(blocked, listOf("nfc", " ", "")))
        assertEquals(
            listOf("bluetooth", "nfc", "zen", "volume"),
            IconSlotPolicy.withOwnedSlots(blocked, listOf("zen", "nfc", "volume", "zen"))
        )
    }

    /**
     * The merged list is the slot modes first, then the left overlay: a slot the user hid with a mode
     * must stay hidden even when it is not left-placed, and a left-placed slot must be hidden even
     * though its mode is 跟随系统.
     */
    @Test
    fun slotModesDecideFirstAndTheLeftOverlayIsLayeredOnTop() {
        val pristine = listOf("zen", "nfc", "wifi")
        val modes = IconSlotPolicy.blockedFor(
            IconSurface.STATUS_BAR,
            pristine,
            IconSlotPolicyConfig(slotModes = mapOf("nfc" to IconSlotMode.HIDE_EVERYWHERE.value))
        )
        assertEquals(listOf("zen", "nfc", "wifi"), modes)
        assertEquals(
            listOf("zen", "nfc", "wifi", "volume"),
            IconSlotPolicy.withOwnedSlots(modes, setOf("volume"))
        )
    }
}
