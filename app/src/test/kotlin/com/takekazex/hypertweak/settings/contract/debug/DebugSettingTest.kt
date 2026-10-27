package com.takekazex.hypertweak.settings.contract.debug

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DebugSettingTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    @Test fun strictTypesNeverCoerceTextOrFractionalIntegers() {
        val bool = DebugSetting("test", "Boolean", false)
        assertEquals(true, bool.decode(true))
        rejects { bool.decode("true") }
        val int = DebugSetting("test", "Int", 0)
        rejects { int.decode(1.5) }
        rejects { int.decode(Long.MAX_VALUE) }
        val float = DebugSetting("test", "Float", 1f)
        rejects { float.decode(Double.POSITIVE_INFINITY) }
        rejects { float.decode("1.5") }
    }
    @Test fun limitsAndNativeContractsAreCheckedBeforeWriting() {
        val columns = DebugSettingsCatalog.entries.getValue("opened_folder_columns")
        assertEquals(4, columns.decode(4))
        rejects { columns.decode(2) }
        rejects { DebugSetting("text", "String", "").decode("x".repeat(16_385)) }
        rejects { DebugSetting("set", "StringSet", emptySet<String>()).decode(JSONArray(listOf("ok", 3))) }
        rejects { DebugSettingsCatalog.entries.getValue("launcher_dock_background").decode("bad") }
        rejects { DebugSettingsCatalog.entries.getValue("camera_legendary_moment_mode").decode("bad") }
    }
    @Test fun retiredAndRuntimeKeysCannotBecomeWritableByPresenceInStorage() {
        assertNull(DebugSettingsCatalog.entries["prefs_epoch"])
        assertNull(DebugSettingsCatalog.entries["debug_access_session"])
        assertFalse(DebugSettingsCatalog.entries.getValue("native_rules_revision").writable)
        rejects { DebugSettingsCatalog.entries.getValue("native_rules_revision").decode(1) }
        assertEquals("Boolean", DebugSettingsCatalog.entries.getValue("systemui_control_center_corner_enabled").type)
        assertEquals("off", DebugSettingsCatalog.entries.getValue("camera_legendary_moment_mode").default)
        assertTrue(DebugSettingsCatalog.entries.size > 400)
        assertEquals(0..4, DebugSettingsCatalog.entries.getValue("icon_tuner_slot_wifi").range)
    }
}
