package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame

class StatusBarAppearanceInputsTest {
    private class Attributes

    @Test fun transientCorrectionLeavesSharedPacketAndOtherInputsUntouched() {
        val attributes = Attributes()
        val original = arrayOf<Any>(attributes, true, false, true, false, true)
        val result = StatusBarAppearanceInputs.withoutTransient(original, Attributes::class.java) as Array<*>
        assertNotSame(original, result)
        assertEquals(true, original[1])
        assertEquals(false, result[1])
        original.indices.filter { it != 1 }.forEach { assertSame(original[it], result[it]) }
    }

    @Test fun ordinaryAndUnreadablePacketsKeepNativeIdentity() {
        val ordinary = arrayOf<Any>(Attributes(), false, false, true, false, true)
        val missingAttributes = arrayOf(null, true, false, true, false, true)
        val malformed = arrayOf<Any>(Attributes(), true, false, true, "unknown", true)
        val otherOwner = arrayOf<Any>(Any(), true, false, true, false, true)
        val wrongSize = arrayOf<Any>(Attributes(), true)
        for (packet in listOf(ordinary, missingAttributes, malformed, otherOwner, wrongSize))
            assertSame(packet, StatusBarAppearanceInputs.withoutTransient(packet, Attributes::class.java))
        assertSame(null, StatusBarAppearanceInputs.withoutTransient(null, Attributes::class.java))
    }
}
