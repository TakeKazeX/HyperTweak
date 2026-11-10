package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class GoogleLiveStateTest {
    private class Model(@JvmField val enabled: Boolean, @JvmField val nativeOn: Boolean)

    @Test fun modelOverrideRestoresNativeFlagsAcrossRepeatedTogglesAndRetirement() {
        val first = Model(false, true)
        val second = Model(false, false)
        val state = BooleanFieldOverrides()
        val fields = listOf("enabled", "nativeOn").map { Model::class.java.getDeclaredField(it).apply { isAccessible = true } }
        state.capture(first, fields)
        state.apply { true }
        state.capture(first, fields) // A refresh cannot replace native false with the forced true.
        state.capture(second, fields)
        state.apply { it === first }
        assertTrue(first.enabled)
        assertFalse(second.enabled)
        state.restore()
        assertFalse(first.enabled)
        assertTrue(first.nativeOn)
        assertFalse(second.nativeOn)
        state.apply { true }
        state.restore()
        assertFalse(first.enabled)
    }

    @Test fun listRestorationPreservesUnrelatedNativeEditsAndPreexistingMembership() {
        val changed = mutableListOf("google", "native")
        val original = mutableSetOf("google")
        val state = CollectionMembershipOverride("google")
        state.apply(changed, false)
        changed += "new-native"
        state.apply(original, true)
        state.restore()
        assertEquals(setOf("google", "native", "new-native"), changed.toSet())
        assertEquals(setOf("google"), original)
        state.apply(changed, false)
        state.restore()
        assertTrue("google" in changed)
    }
}
