package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeViewVisibilityMaskTest {
    @Test fun binderUpdatesStayHiddenAndLatestHostRequestIsRestored() {
        val mask = NativeViewVisibilityMask(8)
        val view = Any()
        assertEquals(8, mask.reconcile(view, 0, true))
        assertEquals(8, mask.hostRequest(view, 4, true))
        assertEquals(8, mask.reconcile(view, 8, true))
        assertEquals(4, mask.reconcile(view, 8, false))
        assertEquals(4, mask.reconcile(view, 4, false))
    }

    @Test fun expandedContainerRemainsIndependentAndHostGoneIsPreserved() {
        val mask = NativeViewVisibilityMask(8)
        val home = Any()
        val expanded = Any()
        assertEquals(8, mask.reconcile(home, 0, true))
        assertEquals(0, mask.hostRequest(expanded, 0, false))
        assertEquals(8, mask.hostRequest(home, 8, true))
        assertEquals(8, mask.reconcile(home, 8, false))
        assertEquals(0, mask.reconcile(expanded, 0, false))
    }

    @Test fun releasingBeforeReconciliationDropsOldRequestForNextAcquisition() {
        val mask = NativeViewVisibilityMask(8)
        val view = Any()
        mask.reconcile(view, 0, true)
        assertEquals(4, mask.hostRequest(view, 4, false))
        assertEquals(8, mask.reconcile(view, 4, true))
        assertEquals(4, mask.reconcile(view, 8, false))
    }
}
