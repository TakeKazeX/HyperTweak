package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test

/** Mockable SDK values are opaque identities here; no Android methods or runtime are required. */
class LeftStateTest {
    private fun state(keyguard: Boolean = false): LeftState {
        val host = FrameLayout(ContextWrapper(null))
        val clock = View(null)
        return LeftState(host, clock, FrameLayout(ContextWrapper(null)), Any(), keyguard, host)
    }

    @Test fun separateRowsNeverShareCloneOrMigrationOwnership() {
        val home = state()
        val lockscreen = state(true)
        val clone = View(null)
        home.clones["wifi"] = clone
        // A clone alone is not permission to hide a native slot: migration is independently acknowledged.
        assertTrue(home.migratedSlots.isEmpty())
        home.migratedSlots = setOf("wifi")
        assertSame(clone, home.clones["wifi"])
        assertTrue(lockscreen.clones.isEmpty())
        assertTrue(lockscreen.migratedSlots.isEmpty())
        assertFalse(home.isKeyguard)
        assertTrue(lockscreen.isKeyguard)
    }

    @Test fun anchorRecordsRetainExactParentIndexAndLayoutIdentity() {
        val row = state(true)
        val params = ViewGroup.LayoutParams(23, 45)
        val entry = AnchorEntry(row.clock, row.leftHost, 3, params)
        row.anchorEntries.add(entry)
        assertSame(row.clock, row.anchorEntries.single().view)
        assertSame(row.leftHost, row.anchorEntries.single().parent)
        assertSame(params, row.anchorEntries.single().layoutParams)
        assertEquals(3, row.anchorEntries.single().index)
        row.clones.clear()
        row.migratedSlots = emptySet()
        assertSame(entry, row.anchorEntries.single()) // Clone cleanup must not discard restoration evidence.
    }

    @Test fun replacementGenerationStartsWithoutPreviousIslandOrAnchorOwnership() {
        val previous = state(true)
        previous.islandShowing = true
        previous.islandHandler = Any()
        previous.anchorEntries.add(AnchorEntry(previous.clock, previous.leftHost, 1, null))
        val replacement = state(true)
        assertFalse(replacement.islandShowing)
        assertNull(replacement.islandHandler)
        assertNull(replacement.anchorWrapper)
        assertNull(replacement.leftContainer)
        assertTrue(replacement.anchorEntries.isEmpty())
    }
}
