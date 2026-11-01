package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.GameBoosterModelScope
import org.junit.Assert.*
import org.junit.Test

class GameBoosterModelScopeTest {
    @Test fun nestedListAndStartCallsRespectDisableAndDoNotLeakAcrossThreadsOrExceptions() {
        val scope = GameBoosterModelScope()
        assertFalse(scope.bypass(true))
        scope.enter()
        try {
            assertTrue(scope.bypass(true))
            assertFalse(scope.bypass(false))
            var otherThreadBypassed = true
            Thread { otherThreadBypassed = scope.bypass(true) }.also { it.start(); it.join() }
            assertFalse(otherThreadBypassed)
            assertThrows(IllegalStateException::class.java) {
                scope.enter()
                try { error("host threw") } finally { scope.leave() }
            }
            assertTrue(scope.bypass(true))
        } finally { scope.leave() }
        assertFalse(scope.bypass(true))
    }
}
