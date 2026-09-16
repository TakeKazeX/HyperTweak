package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the 图标左置 rows against drifting from the slots the hook actually moves.
 *
 * The rows are a hand-written list (each needs its own wording) while the moved slots come from
 * [IconTunerOptions.slotsForLeftPreference], so a toggle added on either side would otherwise
 * appear in the UI without a preview, or exist in the hook without any way to switch it.
 */
class LeftContainerSectionTest {

    @Test
    fun rowsCoverExactlyTheHookSideToggles() {
        assertEquals(
            IconTunerOptions.leftPreferenceKeys,
            LEFT_TOGGLE_ROWS.map { it.key }
        )
    }

    @Test
    fun everyRowHasALabelAndAPreviewGlyph() {
        LEFT_TOGGLE_ROWS.forEach { row ->
            assertTrue("row '${row.key}' has no label resource", row.labelRes != 0)
            val info = IconSlotCatalog.of(row.previewSlot)
            assertNotNull("row '${row.key}' previews '${row.previewSlot}', not in the catalog", info)
        }
    }

    /**
     * The preview must be a slot the hook really moves for that toggle — otherwise the row would
     * advertise a different icon than the one that ends up in the left container. The compound
     * group is the documented exception: it drives the `compound_*` slots, which are not host
     * slots, so it previews the catalog's own compound entry.
     */
    @Test
    fun previewSlotsAreMovedByTheirToggle() {
        LEFT_TOGGLE_ROWS.forEach { row ->
            if (row.key == Preferences.KEY_ICON_LEFT_COMPOUND) {
                assertEquals("compound_icon", row.previewSlot)
            } else {
                assertTrue(
                    "row '${row.key}' previews '${row.previewSlot}', " +
                        "but the hook moves ${IconTunerOptions.slotsForLeftPreference(row.key)}",
                    row.previewSlot in IconTunerOptions.slotsForLeftPreference(row.key)
                )
            }
        }
    }

    @Test
    fun everyMovedSlotIsVisibleInTheCatalog() {
        IconTunerOptions.leftPreferenceKeys
            .filterNot { it == Preferences.KEY_ICON_LEFT_COMPOUND }
            .flatMap { IconTunerOptions.slotsForLeftPreference(it) }
            .forEach { slot ->
                assertNotNull("moved slot '$slot' has no catalog entry", IconSlotCatalog.of(slot))
            }
    }
}
