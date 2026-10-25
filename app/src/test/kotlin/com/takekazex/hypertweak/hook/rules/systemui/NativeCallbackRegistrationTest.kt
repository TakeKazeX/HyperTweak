package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.*
import org.junit.Test

class NativeCallbackRegistrationTest {
    interface Listener
    interface Generic<T> { fun addCallback(callback: T); fun removeCallback(callback: T) }
    open class Controller : Generic<Listener> {
        val callbacks = mutableListOf<Listener>()
        var rejectRelease = false
        override fun addCallback(callback: Listener) = Unit
        override fun removeCallback(callback: Listener) {
            check(!rejectRelease)
            callbacks.remove(callback)
        }
    }
    class Inherited : Controller()
    class Ambiguous {
        fun addCallback(callback: Listener) = Unit
        fun addCallback(callback: Runnable) = Unit
        fun removeCallback(callback: Listener) = Unit
        fun removeCallback(callback: Runnable) = Unit
    }
    class Both : Listener, Runnable { override fun run() = Unit }
    class Erased { fun addCallback(callback: Any) = Unit }
    class Invalid {
        fun removeCallback(callback: Listener): Int = callback.hashCode()
        companion object { @JvmStatic fun addCallback(callback: Listener) = Unit }
    }

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
    @Test fun `typed inherited removal ignores Object bridge and releases only owned callback`() {
        val controller = Inherited()
        val native = Both()
        val owned = Both()
        controller.callbacks += listOf(native, owned)
        val method = NativeCallbackRegistration.select(controller.javaClass.methods, "removeCallback", Both::class.java)!!
        assertFalse(method.isBridge)
        val records = mutableListOf(arrayOf<Any>(controller, owned, method))
        NativeCallbackRegistration.release(records) { throw it }
        assertEquals(listOf(native), controller.callbacks)
        assertTrue(records.isEmpty())
    }
    @Test fun `failed release retains exact lease and a later generation can retry it`() {
        val controller = Controller()
        val owned = Both()
        controller.callbacks += owned
        val method = NativeCallbackRegistration.select(controller.javaClass.methods, "removeCallback", Both::class.java)!!
        val records = mutableListOf(arrayOf<Any>(controller, owned, method))
        controller.rejectRelease = true
        var failures = 0
        NativeCallbackRegistration.release(records) { failures++ }
        assertEquals(1, failures)
        assertEquals(1, records.size)
        controller.rejectRelease = false
        NativeCallbackRegistration.release(records) { throw it }
        assertTrue(records.isEmpty())
        assertTrue(controller.callbacks.isEmpty())
    }
    @Test fun `static nonvoid and genuinely ambiguous removal contracts are rejected`() {
        assertNull(NativeCallbackRegistration.select(Invalid::class.java.methods, "addCallback", Both::class.java))
        assertNull(NativeCallbackRegistration.select(Invalid::class.java.methods, "removeCallback", Both::class.java))
        assertNull(NativeCallbackRegistration.select(Ambiguous::class.java.methods, "removeCallback", Both::class.java))
    }
}
