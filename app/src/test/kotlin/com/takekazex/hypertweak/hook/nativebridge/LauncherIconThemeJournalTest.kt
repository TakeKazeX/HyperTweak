package com.takekazex.hypertweak.hook.nativebridge

import org.junit.Assert.*
import org.junit.Test

class LauncherIconThemeJournalTest {
    private class Store : LauncherIconThemeJournal.Port {
        val host = mutableMapOf<String, String>()
        val records = mutableMapOf<String, LauncherIconThemeJournal.Record>()
        var accept = true
        override fun readHost(key: String) = host[key]
        override fun writeHost(key: String, value: String?): Boolean {
            if (!accept) return false
            if (value == null) host.remove(key) else host[key] = value
            return true
        }
        override fun load(key: String) = records[key]
        override fun save(key: String, record: LauncherIconThemeJournal.Record?): Boolean {
            if (record == null) records.remove(key) else records[key] = record
            return true
        }
    }
    @Test fun `absence and explicit host choices restore after process replacement`() {
        for (original in listOf(null, "0", "1", "123456")) {
            val store = Store(); original?.let { store.host["color"] = it }
            val owner = LauncherIconThemeJournal(store)
            owner.write("color", "42"); owner.write("color", "43")
            LauncherIconThemeJournal(store).restore("color")
            assertEquals(original, store.host["color"]); assertTrue(store.records.isEmpty())
        }
    }
    @Test fun `a newer native customization edit is preserved on disable`() {
        val store = Store(); val owner = LauncherIconThemeJournal(store)
        owner.write("color", "42"); store.host["color"] = "user"
        owner.restore("color")
        assertEquals("user", store.host["color"]); assertTrue(store.records.isEmpty())
    }
    @Test fun `mode changes read original color and adopt later native edits`() {
        val store = Store(); store.host["color"] = "0"
        val owner = LauncherIconThemeJournal(store)
        owner.write("color", "42")
        assertEquals("0", owner.original("color"))
        owner.write("color", "43")
        assertEquals("0", LauncherIconThemeJournal(store).original("color"))
        store.host["color"] = "native"
        assertEquals("native", owner.original("color"))
        owner.write("color", "44"); owner.restore("color")
        assertEquals("native", store.host["color"])
    }
    @Test fun `failed provider write retains recovery evidence and can retry`() {
        val store = Store(); store.host["color"] = "original"
        val owner = LauncherIconThemeJournal(store); store.accept = false
        assertThrows(IllegalStateException::class.java) { owner.write("color", "42") }
        assertEquals("original", store.records["color"]!!.before)
        store.accept = true; owner.write("color", "42")
        store.accept = false
        assertThrows(IllegalStateException::class.java) { owner.restore("color") }
        assertNotNull(store.records["color"])
        store.accept = true; owner.restore("color")
        assertEquals("original", store.host["color"]); assertTrue(store.records.isEmpty())
    }
}
