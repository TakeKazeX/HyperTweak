package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class IslandTemplatePublicationTest {
    @Test fun `relayout scope alone does not snap native state or gesture tweens`() {
        val relayout = IslandTemplatePublication()
        val width = IslandTemplatePublication()
        relayout.run {
            assertFalse(width.active)
            width.run { assertTrue(width.active) }
            assertFalse(width.active)
        }
        assertFalse(relayout.active)
        assertFalse(width.active)
    }

    @Test fun `ordinary updates do not replay configuration publication after template binding`() {
        val bindings = IslandTemplateRebinds()
        val real = Any()
        val fake = Any()
        assertFalse(bindings.bound(real)) // Initial creation or a track update.
        bindings.expect(real)
        bindings.expect(fake)
        assertTrue(bindings.bound(real))
        assertFalse(bindings.bound(real)) // Subsequent ordinary update.
        assertTrue(bindings.bound(fake)) // Asynchronous fake binding completes separately.
        assertFalse(bindings.bound(fake))
    }

    @Test fun `nested template transactions release scope even on failure`() {
        val scope = IslandTemplatePublication()
        assertFalse(scope.active)
        scope.run {
            assertTrue(scope.active)
            runCatching { scope.run { error("native width callback") } }
            assertTrue(scope.active)
        }
        assertFalse(scope.active)
    }

    @Test fun `template transaction cannot change animations on another thread`() {
        val scope = IslandTemplatePublication()
        var otherThread = true
        scope.run {
            val thread = Thread { otherThread = scope.active }
            thread.start(); thread.join()
            assertTrue(scope.active)
        }
        assertFalse(otherThread)
        assertFalse(scope.active)
    }

    @Test fun `manual expansion exemption belongs to its expanded task`() {
        assertTrue(IslandExpansionOwnership.clearInheritedUserExpansion(true, "timer", "music"))
        assertTrue(IslandExpansionOwnership.clearInheritedUserExpansion(true, "timer", null))
        assertTrue(IslandExpansionOwnership.clearInheritedUserExpansion(true, null, "music"))
        assertFalse(IslandExpansionOwnership.clearInheritedUserExpansion(true, "music", "music"))
        assertFalse(IslandExpansionOwnership.clearInheritedUserExpansion(false, "timer", "music"))
    }
}
