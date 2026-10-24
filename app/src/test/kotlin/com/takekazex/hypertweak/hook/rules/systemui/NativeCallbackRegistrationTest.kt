package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.*
import org.junit.Test

class NativeCallbackRegistrationTest {
    interface Listener
    interface Generic<T> { fun addCallback(callback: T) }
    class Controller : Generic<Listener> {
        override fun addCallback(callback: Listener) = Unit
    }
    class Ambiguous {
        fun addCallback(callback: Listener) = Unit
        fun addCallback(callback: Runnable) = Unit
    }
    class Both : Listener, Runnable { override fun run() = Unit }
    class Erased { fun addCallback(callback: Any) = Unit }

    @Test fun `compiler Object bridge cannot make native registration ambiguous`() {
        val methods = Controller::class.java.methods
        assertEquals(2, methods.count { it.name == "addCallback" })
        assertEquals(Listener::class.java,
            NativeCallbackRegistration.select(methods, "addCallback", Both::class.java)!!.parameterTypes.single())
    }
    @Test fun `two real typed registrations fail closed`() {
        assertNull(NativeCallbackRegistration.select(Ambiguous::class.java.methods, "addCallback", Both::class.java))
    }
    @Test fun `Object alone is not a verified native callback contract`() {
        assertNull(NativeCallbackRegistration.select(Erased::class.java.methods, "addCallback", Both::class.java))
    }
}
