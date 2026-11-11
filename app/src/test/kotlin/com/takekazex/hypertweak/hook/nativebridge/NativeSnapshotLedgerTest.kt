package com.takekazex.hypertweak.hook.nativebridge

import com.takekazex.hypertweak.settings.contract.NativeDockGlassGeometry
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import org.junit.Assert.*
import org.junit.Test

class NativeSnapshotLedgerTest {
    @Test fun `late daemon callback cannot undo a committed app snapshot`() {
        val ledger = NativeSnapshotLedger()
        val old = NativeRuleStateSnapshot(true, 4, false, 10, 3)
        val new = old.copy(columns = 5, revision = 11)
        assertTrue(ledger.offer(old)); assertTrue(ledger.offer(new))
        assertFalse(ledger.offer(old)); assertEquals(new, ledger.latest())
    }
    @Test fun `reset epoch can replace a larger previous commit identity`() {
        val ledger = NativeSnapshotLedger()
        assertTrue(ledger.offer(NativeRuleStateSnapshot(true, 5, true, 800, 3)))
        val reset = NativeRuleStateSnapshot(false, 3, false, 0, 4)
        assertTrue(ledger.offer(reset))
        assertFalse(ledger.offer(reset.copy(epoch = 3, revision = 900)))
        assertEquals(reset, ledger.latest())
    }
    @Test fun `same commit conflict is rejected but replay is allowed`() {
        val ledger = NativeSnapshotLedger()
        val current = NativeRuleStateSnapshot(false, 4, true, 1, 1)
        assertTrue(ledger.offer(current)); assertTrue(ledger.offer(current))
        assertFalse(ledger.offer(current.copy(hidden = true)))
        assertFalse(ledger.offer(current.copy(assistantWidgets = true)))
    }
    @Test fun `commit order survives a lower clock and cannot overflow`() {
        assertEquals(901, NativeSnapshotLedger.nextRevision(900, 1))
        assertEquals(1000, NativeSnapshotLedger.nextRevision(900, 1000))
        assertThrows(IllegalArgumentException::class.java) { NativeSnapshotLedger.nextRevision(Long.MAX_VALUE, 1) }
    }
    @Test fun `partial malformed metadata and invalid columns never become defaults`() {
        for (value in listOf<Any>(-1L, "1", 1)) assertNull(NativeRuleStateSnapshot.read {
            mapOf(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to value)
        })
        assertNull(NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS to 6) })
        assertNull(NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT to 1) })
    }
    @Test fun `replacement generation retains authority using framework transferable values`() {
        val previous = NativeSnapshotLedger()
        val committed = NativeRuleStateSnapshot(true, 5, true, 100, 3, assistantWidgets = true)
        previous.offer(committed)
        val replacement = NativeSnapshotLedger()
        replacement.restore(previous.save())
        assertFalse(replacement.offer(committed.copy(columns = 4, revision = 99)))
        assertEquals(committed, replacement.latest())
        replacement.restore(arrayOf<Any>(false, 3, false, "wrong", 5L))
        assertEquals(committed, replacement.latest())
    }
    @Test fun `Pad Dock survives reload and the retired icon bit stays retired`() {
        val stock = NativeRuleStateSnapshot(false, 3, false)
        val previous = NativeSnapshotLedger().apply { offer(stock) }
        val oldIconRefresh = previous.save()!! + true
        assertEquals(stock, NativeSnapshotLedger().apply { restore(oldIconRefresh) }.latest())
        val selected = stock.copy(nativePadDock = true, revision = 1)
        assertTrue(previous.offer(selected))
        assertEquals(selected, NativeSnapshotLedger().apply { restore(previous.save()) }.latest())
        assertNull(NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_NATIVE_PAD_DOCK to "true") })
    }
    @Test fun `Dock glass geometry survives reload and rejects malformed values`() {
        val geometry = NativeDockGlassGeometry(height = 72, margin = 16, bottom = 8, radius = 24).pack()
        val stock = NativeRuleStateSnapshot(false, 3, false)
        val previous = NativeSnapshotLedger().apply { offer(stock) }
        assertEquals(14, previous.save()!!.size)
        val custom = stock.copy(dockGlass = geometry, revision = 1)
        assertTrue(previous.offer(custom))
        assertEquals(17, previous.save()!!.size)
        assertEquals(custom, NativeSnapshotLedger().apply { restore(previous.save()) }.latest())
        assertEquals(custom.copy(revision = 2, dockGlass = NativeDockGlassGeometry.DEFAULT_PACKED),
            NativeRuleMutation.apply(custom, PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS, NativeDockGlassGeometry.DEFAULT_PACKED, 2))
        assertTrue(runCatching { NativeRuleMutation.apply(custom, PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS, 121, 2) }.isFailure)
        assertNull(NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS to (65 shl 24)) })
        assertNull(NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS to -1) })
        assertNull(NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS to 1L) })
        assertEquals(NativeDockGlassGeometry.DEFAULT_PACKED, NativeRuleStateSnapshot.read { emptyMap<String, Any>() }!!.dockGlass)
    }
    @Test fun `adaptive Dock width rides in the geometry height byte`() {
        val adaptive = NativeDockGlassGeometry(height = 72, margin = 16, bottom = 8, radius = 24, adaptive = true)
        val packed = adaptive.pack()
        assertEquals(72 or 0x80 or (16 shl 8) or (8 shl 16) or (24 shl 24), packed)
        assertEquals(adaptive, NativeDockGlassGeometry.unpack(packed))
        assertEquals(adaptive.copy(adaptive = false), NativeDockGlassGeometry.unpack(packed and 0x80.inv()))
        assertEquals(packed, NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS to packed) }!!.dockGlass)
        assertEquals(packed, NativeRuleMutation.apply(NativeRuleStateSnapshot(false, 3, false),
            PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS, packed, 1).dockGlass)
        // The flag is no height: 0x80 | 121 still carries an out-of-range height.
        assertNull(NativeDockGlassGeometry.unpack(0x80 or 121))
    }
}
