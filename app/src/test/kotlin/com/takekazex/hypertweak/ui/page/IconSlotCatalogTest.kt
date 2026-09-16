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

    @Test
    fun unknownSlotStillGetsAReadableFallback() {
        assertEquals(null, IconSlotCatalog.of("not_a_host_slot"))
        assertEquals("Some future slot", IconSlotCatalog.fallbackLabel("some_future_slot"))
    }
}
