package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class IslandTemplatePublicationTest {
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
