package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.*
import org.junit.Test

class NotificationTextColorPolicyTest {
    private val prefix = "com.android.systemui.statusbar.notification.row.wrapper."

    @Test fun lightHeadsUpContextIsNotForcedDarkByGlassMaterial() {
        assertFalse(NotificationTextColorPolicy.usesDarkTextContext(false, false, false))
        assertEquals(0xff000000.toInt(), NotificationTextColorPolicy.neutral(
            NotificationTextColorPolicy.usesDarkTextContext(false, false, false)))
        // Ordinary glass rows have a native dark context, even in global light mode.
        assertTrue(NotificationTextColorPolicy.usesDarkTextContext(true, false, false))
        assertEquals(-1, NotificationTextColorPolicy.neutral(true))
        assertTrue(NotificationTextColorPolicy.usesDarkTextContext(false, true, false))
        assertTrue(NotificationTextColorPolicy.usesDarkTextContext(false, false, true))
    }

    @Test fun dragDownHandoverRetainsHeadsUpBranchUntilBothNativeFlagsClear() {
        assertTrue(NotificationTextColorPolicy.isHeadsUp(true, false))
        assertTrue(NotificationTextColorPolicy.isHeadsUp(false, true))
        assertTrue(NotificationTextColorPolicy.isHeadsUp(true, true))
        assertFalse(NotificationTextColorPolicy.isHeadsUp(false, false))
    }

    @Test fun groupedChildUsesVisibleSummaryContextAndReturnsToItsOwnBackground() {
        data class Row(var headsUp: Boolean, var dragging: Boolean = false)
        val summary = Row(true)
        val child = Row(false)
        fun night(parent: Row?, childHidden: Boolean, parentHidden: Boolean): Boolean {
            val owner = NotificationTextColorPolicy.backgroundOwner(child, parent, childHidden, parentHidden)
            // GLASS_DARK in light mode: only HUN uses the native light context.
            return !NotificationTextColorPolicy.isHeadsUp(owner.headsUp, owner.dragging)
        }
        assertEquals(0xff000000.toInt(), NotificationTextColorPolicy.neutral(night(summary, true, false)))
        summary.headsUp = false
        summary.dragging = true
        assertFalse(night(summary, true, false))
        summary.dragging = false
        assertTrue(night(summary, true, false))
        summary.headsUp = true
        assertTrue(night(summary, false, false)) // Expanded child has its own background.
        assertTrue(night(summary, true, true)) // Hidden summary is not the background owner.
        assertTrue(night(null, true, false)) // Detached child cannot retain its old HUN parent.
        child.headsUp = true
        assertFalse(night(null, false, false))
    }

    @Test fun customSubclassIsExcludedEvenWhenItInheritsNativeTemplate() {
        assertFalse(NotificationTextColorPolicy.ownsTemplate(listOf(
            prefix + "MiuiNotificationDecoratedCustomViewWrapper", prefix + "NotificationTemplateViewWrapper")))
        assertFalse(NotificationTextColorPolicy.ownsTemplate(listOf(prefix + "UnknownViewWrapper")))
        assertTrue(NotificationTextColorPolicy.ownsTemplate(listOf(
            prefix + "NewStandardSubclass", prefix + "NotificationTemplateViewWrapper")))
        assertTrue(NotificationTextColorPolicy.ownsTemplate(listOf(prefix + "MiuiNotificationInboxViewWrapper")))
    }

    @Test fun neutralizationPreservesStockAlphaAndUnrelatedAppColors() {
        val stock = setOf(0x99334455.toInt(), 0xff334455.toInt())
        assertEquals(0x99000000.toInt(), NotificationTextColorPolicy.replaceStock(0x99334455.toInt(), stock, false))
        assertEquals(0x99ffffff.toInt(), NotificationTextColorPolicy.replaceStock(0x99334455.toInt(), stock, true))
        assertEquals(0xff125678.toInt(), NotificationTextColorPolicy.replaceStock(0xff125678.toInt(), stock, true))
        assertEquals(0x00125678, NotificationTextColorPolicy.replaceStock(0x00125678, stock, false))
    }

    @Test fun repeatedCachedCallbacksRetainOriginalButNativeRebindReplacesIt() {
        val native = 0xff445566.toInt()
        val applied = NotificationTextColorPolicy.neutral(false)
        val original = NotificationTextColorPolicy.original(native, null, null)
        assertEquals(native, NotificationTextColorPolicy.original(applied, original, applied))
        val rebound = 0xff667788.toInt()
        assertEquals(rebound, NotificationTextColorPolicy.original(rebound, original, applied))
        val dark = NotificationTextColorPolicy.neutral(true)
        assertEquals(native, NotificationTextColorPolicy.original(applied, original, applied))
        assertEquals(-1, dark)
        assertEquals(0xb3ffffff.toInt(), NotificationTextColorPolicy.secondary(true))
        assertEquals(0x99000000.toInt(), NotificationTextColorPolicy.secondary(false))
    }
}
