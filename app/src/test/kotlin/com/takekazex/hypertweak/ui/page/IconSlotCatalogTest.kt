package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the icon-tuner slot list against regressions of the "槽位显示的都是英文" report.
 *
 * The page renders [IconSlotCatalog.slots] and takes every row's title from the catalog, so a slot
 * that is listed without an entry silently falls back to its raw wire name (English) — exactly the
 * bug. These checks keep the list, the labels, and the module slots in lockstep.
 */
class IconSlotCatalogTest {

    @Test
    fun everyRenderedSlotHasALabelAndPreview() {
        IconSlotCatalog.slots.forEach { slot ->
            val info = IconSlotCatalog.of(slot)
            assertNotNull("slot '$slot' is rendered but has no catalog entry", info)
            assertTrue("slot '$slot' has no label resource", info!!.labelRes != 0)
        }
    }

    @Test
    fun catalogHasNoRowsThePageWouldNotRender() {
        val rendered = IconSlotCatalog.slots.toSet()
        val orphaned = IconSlotCatalog.bySlot.keys - rendered
        assertEquals("catalog entries that no row renders: $orphaned", emptySet<String>(), orphaned)
    }

    @Test
    fun renderedSlotsAreUniqueAndCoverTheModuleSlots() {
        assertEquals(IconSlotCatalog.slots.size, IconSlotCatalog.slots.toSet().size)
        assertTrue(IconSlotCatalog.slots.containsAll(IconSlotPolicy.MODULE_SLOTS))
    }

    /**
     * The icon-order page shows [IconSlotCatalog.orderSlots] as the slot order, and a stored custom
     * order is a prefix list: `IconSlotPolicy.applyCustomOrder` puts the stored slots first and
     * everything else behind them in host order. A host slot the order list cannot draw is
     * therefore pushed to the end of the status bar by the first drag, which is why every name
     * SystemUI's `config_statusBarIcons` declares must be orderable and must appear in the host's
     * own order.
     */
    @Test
    fun orderListCoversEveryHostSlotInHostOrder() {
        assertEquals(HOST_SLOT_ORDER, IconSlotCatalog.orderSlots.filter { it in HOST_SLOT_ORDER })
        assertTrue(IconSlotCatalog.orderSlots.containsAll(HOST_SLOT_ORDER))
        assertTrue(IconSlotCatalog.slots.containsAll(IconSlotCatalog.orderSlots))
    }

    @Test
    fun settingsOnlySlotModeKeyIsNotOrderable() {
        assertEquals(false, IconSlotCatalog.of("compound_icon")?.orderable)
        assertTrue("compound_icon" !in IconSlotCatalog.orderSlots)
    }

    @Test
    fun unknownSlotStillGetsAReadableFallback() {
        assertEquals(null, IconSlotCatalog.of("not_a_host_slot"))
        assertEquals("Some future slot", IconSlotCatalog.fallbackLabel("some_future_slot"))
    }

    private companion object {
        /** `com.android.systemui.R.array.config_statusBarIcons`, OS4.0.0.15/0.25. */
        val HOST_SLOT_ORDER = listOf(
            "handle", "network_speed", "mute", "micphone", "headset", "mikey", "privacy_mode",
            "nfc", "gps", "missed_call", "managed_profile", "second_space", "ime", "cast",
            "location", "stealth", "tty", "alarm_clock", "vpn", "ethernet", "handle_battery",
            "bluetooth", "bluetooth_handsfree_battery", "hotspot", "sound_box_group", "stereo",
            "sound_box_screen", "sound_box", "wireless_headset", "zen", "volume", "dist_compute",
            "camera", "glasses", "car", "tv", "pc", "pad", "phone", "hd", "airplane", "mobile",
            "demo_mobile", "no_sim", "wifi", "demo_wifi"
        )
    }
}
