package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.*
import org.junit.Test

class SettingBackupTransactionTest {
    @Test fun `failed live writes retain the original backup`() {
        val values = mutableMapOf<String, String?>("backup" to "original", "live" to "module")
        val store = SettingBackupTransaction({ values[it] }, { key, value ->
            if (key == "live") false else { values[key] = value; true }
        })
        assertFalse(store.restore("backup", "live")); assertEquals("original", values["backup"])
        assertEquals("module", values["live"])
    }
    @Test fun `failed reads cannot be mistaken for missing originals`() {
        var writes = 0
        val store = SettingBackupTransaction({ error("provider not ready") }, { _, _ -> writes++; true })
        assertTrue(runCatching { store.record("backup", null) }.isFailure)
        assertEquals(0, writes)
    }
    @Test fun `unset originals can be restored and successful writes release the backup`() {
        val values = mutableMapOf<String, String?>()
        val store = SettingBackupTransaction({ values[it] }, { key, value -> values[key] = value; true })
        assertTrue(store.record("backup", null)); values["live"] = "module"
        assertTrue(store.restore("backup", "live")); assertNull(values["live"]); assertFalse(store.has("backup"))
    }
    @Test fun `failed backup acknowledgement prevents ownership and an existing backup cannot be overwritten`() {
        val values = mutableMapOf<String, String?>()
        val store = SettingBackupTransaction({ values[it] }, { _, _ -> false })
        assertFalse(store.record("backup", "original")); assertFalse(store.has("backup"))
        values["backup"] = "original"; assertTrue(store.record("backup", "module"))
        assertEquals("original", values["backup"])
    }
}
