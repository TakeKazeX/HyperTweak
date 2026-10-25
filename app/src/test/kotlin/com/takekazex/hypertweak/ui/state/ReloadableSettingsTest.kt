package com.takekazex.hypertweak.ui.state

import org.junit.Assert.*
import org.junit.Test

class ReloadableSettingsTest {
    @Test fun rebindUsesTheSameReadersAndResetUpdatesEveryExistingState() {
        val stored = mutableMapOf<String, Any>("flag" to true, "size" to 24)
        val settings = ReloadableSettings()
        val flag = settings.preference { stored["flag"] as? Boolean ?: false }
        val size = settings.preference { stored["size"] as? Int ?: 16 }
        assertTrue(flag.value)
        assertEquals(24, size.value)
        flag.value = false // Optimistic UI edits survive until an authoritative reload.
        assertTrue(stored["flag"] as Boolean)
        settings.reload()
        assertTrue(flag.value)
        stored.clear()
        settings.reload()
        assertFalse(flag.value)
        assertEquals(16, size.value)
    }
}
