package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class IslandRenderScopeTest {
    @Test fun `app close callback cannot project a nested scheduling transaction`() {
        val scope = IslandRenderScope()
        val music = Any()
        val timer = Any()
        scope.render(music) {
            assertSame(music, scope.get())
            scope.native {
                assertNull(scope.get())
                scope.render(timer) { assertSame(timer, scope.get()) }
                assertNull(scope.get())
            }
            assertSame(music, scope.get())
        }
        assertNull(scope.get())
    }

    @Test fun `failed host callback restores enclosing scope and leaves no projection behind`() {
        val scope = IslandRenderScope()
        val music = Any()
        scope.render(music) {
            try { scope.native<Unit> { error("host failure") } } catch (_: IllegalStateException) { }
            assertSame(music, scope.get())
        }
        try { scope.render<Unit>(music) { error("render failure") } } catch (_: IllegalStateException) { }
        assertNull(scope.get())
    }
}
