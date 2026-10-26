package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import org.junit.Assert.*
import org.junit.Test

class RuntimeSettingsReadCacheTest {
    @Test fun acceptedAndRestoredSnapshotsInvalidateTheSameCacheUsedByPreferences() {
        val ledger = RuntimeSettingsStore::class.java.getDeclaredField("ledger").apply { isAccessible = true }.get(RuntimeSettingsStore)
        val field = RuntimeSettingsLedger::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        val previous = field.get(ledger)
        val key = IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE
        val snapshot = RuntimeSettingsSnapshot.read(1, mapOf(key to 1), setOf(RuntimeSettingsGroups.ICONS))!!
        try {
            field.set(ledger, null)
            RuntimeSettingsReadCache.memo[key] = 0
            assertTrue(RuntimeSettingsStore.accept(snapshot))
            assertNull(RuntimeSettingsReadCache.memo[key])
            RuntimeSettingsReadCache.memo[key] = 7
            assertFalse(RuntimeSettingsStore.accept(snapshot))
            assertEquals(7, RuntimeSettingsReadCache.memo[key]) // A stale packet cannot disturb live reads.
            val saved = RuntimeSettingsStore.save()
            field.set(ledger, null)
            RuntimeSettingsStore.restore(saved)
            assertNull(RuntimeSettingsReadCache.memo[key])
            assertEquals(1, RuntimeSettingsStore.value(key))
        } finally {
            field.set(ledger, previous)
            RuntimeSettingsReadCache.memo.clear()
        }
    }
}
