package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class ReadyIslandControllerTest {
    private class Component
    private class Coordinator
    private class View {
        @Volatile var component: Component? = null
        var coordinator: Coordinator? = null
        var calls = 0
        var fail = false
        val controller = Any()
        fun controller(): Any {
            calls++
            check(component != null) { "component was not initialized" }
            check(!fail) { "unrelated host failure" }
            return controller
        }
    }

    private fun access() = ReadyIslandController.resolve(View::class.java,
        View::class.java.getDeclaredMethod("controller"), Component::class.java, Coordinator::class.java)!!

    @Test fun `early callbacks never enter throwing getter and later readiness is retried`() {
        val view = View()
        val access = access()
        repeat(5) { assertNull(access.get(view)) }
        assertEquals(0, view.calls)
        view.component = Component()
        assertNull(access.get(view)) // Native event coordinator is not ready yet.
        assertEquals(0, view.calls)
        view.coordinator = Coordinator()
        assertSame(view.controller, access.get(view))
        assertEquals(1, view.calls)
        view.component = null
        assertNull(access.get(view)) // Readiness must never be cached across view lifecycle changes.
        assertEquals(1, view.calls)
    }

    @Test fun `ready host failures still reach the existing failure boundary`() {
        val view = View().apply { component = Component(); coordinator = Coordinator(); fail = true }
        val failure = assertThrows(InvocationTargetException::class.java) { access().get(view) }
        assertEquals("unrelated host failure", failure.cause?.message)
    }

    private class Ambiguous { var first: Component? = null; var second: Component? = null; fun controller() = Any() }

    @Test fun `missing or ambiguous readiness fields reject the contract`() {
        assertNull(ReadyIslandController.resolve(View::class.java,
            View::class.java.getDeclaredMethod("controller"), String::class.java))
        assertNull(ReadyIslandController.resolve(Ambiguous::class.java,
            Ambiguous::class.java.getDeclaredMethod("controller"), Component::class.java))
    }
}
