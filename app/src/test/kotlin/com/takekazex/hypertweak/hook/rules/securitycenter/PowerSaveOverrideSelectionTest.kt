package com.takekazex.hypertweak.hook.rules.securitycenter

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerSaveOverrideSelectionTest {
    private val stored = mapOf("refresh" to true, "haptic" to true, "sounds" to false, "wake" to true)
    @Test fun `editing with old master off cannot awaken dormant siblings`() {
        assertEquals(mapOf("refresh" to true, "haptic" to false, "sounds" to false, "wake" to false),
            PowerSaveOverrideSelection.edit(false, stored, "refresh", true))
    }
    @Test fun `editing with old master on preserves the effective individual choices`() {
        assertEquals(stored + ("haptic" to false), PowerSaveOverrideSelection.edit(true, stored, "haptic", false))
    }
    @Test fun `turning off an already ineffective feature preserves all off state`() {
        assertEquals(stored.mapValues { false }, PowerSaveOverrideSelection.edit(false, stored, "refresh", false))
    }
}
