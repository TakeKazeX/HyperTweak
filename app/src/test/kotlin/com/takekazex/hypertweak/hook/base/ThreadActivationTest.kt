package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class ThreadActivationTest {
    @Test fun `nested host initialization keeps the outer override and exceptions release it`() {
        val scope = ThreadActivation()
        assertTrue(runCatching {
            scope.within {
                assertTrue(scope.active)
                scope.within { assertTrue(scope.active) }
                assertTrue(scope.active)
                error("native XML inflation failed")
            }
        }.isFailure)
        assertFalse(scope.active)
    }
    @Test fun `UI override cannot leak to camera operations on another thread`() {
        val scope = ThreadActivation()
        scope.within {
            assertFalse(CompletableFuture.supplyAsync { scope.active }.get())
            assertTrue(scope.active)
        }
        assertFalse(scope.active)
    }
}
