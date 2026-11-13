package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow

import org.junit.Assert.*
import org.junit.Test

class XiaomiShadeBarSelectionTest {
    private fun root(mode: Int = 1, type: Int = 1, transparent: Boolean = false,
                     name: String? = "example.Activity", display: Int = 0) =
        XiaomiShadeBarSelection.Root(display, mode, type, transparent, name)

    @Test fun transparentAndFreeformTargetsDoNotDisplaceUnderlyingHome() {
        val selection = XiaomiShadeBarSelection()
        assertTrue(selection.onAppTransition(0, root(transparent = true),
            listOf(root(mode = 5), root(transparent = true), root(type = 2))))
        assertEquals(0, selection.current(false, true))
        assertTrue(selection.onAppTransition(0, root(transparent = true,
            name = "com.miui.gallery.activity.InternalPhotoPageActivity"), listOf(root(type = 2))))
        assertEquals(1, selection.current(false, true))
    }

    @Test fun missingTaskAndOtherDisplayEventsPreserveLastSelection() {
        val selection = XiaomiShadeBarSelection()
        assertTrue(selection.onAppTransition(0, root(), emptyList()))
        assertFalse(selection.onAppTransition(0, null, listOf(root(mode = 5))))
        assertFalse(selection.onAppTransition(1, root(type = 2, display = 1), emptyList()))
        assertEquals(1, selection.current(false, true))
    }

    @Test fun homeExceptionFlipAndTokenRemovalFollowNativeSelection() {
        val selection = XiaomiShadeBarSelection()
        assertEquals(0, selection.current(false, true))
        selection.onAppTransition(0, root(type = 2,
            name = "com.android.phone.settings.MobileNetworkSettings"), emptyList())
        assertEquals(1, selection.current(false, true))
        assertEquals(0, selection.current(true, true))
        assertEquals(0, selection.current(false, false))
        assertEquals(1, selection.current(false, true))
        selection.invalidate()
        assertEquals(0, selection.current(false, true))
    }
}
