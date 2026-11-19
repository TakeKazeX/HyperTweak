package com.takekazex.hypertweak.hook.rules.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistRestoreLedgerTest {
    private val never: (String) -> Boolean = { false }

    @Test fun blocksAddsPowerKeeperDidNotUndo() {
        val ledger = AllowlistRestoreLedger()
        assertEquals(emptyList<String>(), ledger.filterAdds(listOf("com.tencent.mm", "com.android.deskclock"), true, never))
    }

    @Test fun restoresOwnRemovalOnlyOnce() {
        val ledger = AllowlistRestoreLedger()
        ledger.recordRemoval("user.app")
        assertEquals(listOf("user.app"), ledger.filterAdds(listOf("com.tencent.mm", "user.app"), true, never))
        assertEquals(emptyList<String>(), ledger.filterAdds(listOf("user.app"), true, never))
    }

    @Test fun nativeRestoreWhileOffConsumesRecord() {
        val ledger = AllowlistRestoreLedger()
        ledger.recordRemoval("user.app")
        assertEquals(listOf("user.app", "com.tencent.mm"), ledger.filterAdds(listOf("user.app", "com.tencent.mm"), false, never))
        assertEquals(emptyList<String>(), ledger.filterAdds(listOf("user.app"), true, never))
    }

    @Test fun collaboratorExemptionPassesWithoutRecord() {
        val ledger = AllowlistRestoreLedger()
        val gms = "com.google.android.gms"
        assertEquals(listOf(gms), ledger.filterAdds(listOf(gms, "com.tencent.mm"), true) { it == gms })
    }

    @Test fun hotReloadSnapshotCarriesPendingRestores() {
        val old = AllowlistRestoreLedger().apply { recordRemoval("user.app") }
        val replacement = AllowlistRestoreLedger().apply { restore(old.snapshot()) }
        assertTrue(replacement.filterAdds(listOf("user.app"), true, never) == listOf("user.app"))
    }
}
