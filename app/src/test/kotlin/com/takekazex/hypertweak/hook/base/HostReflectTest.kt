package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

/** EzHookTool 1.3.1 semantics that the module-owned helpers must keep. */
class HostReflectTest {
    @Suppress("unused")
    open class Base {
        open fun key(): String = "base"
        private fun hidden(): String = "base-private"
        fun pick(value: Any?): String = "base-any"
    }

    @Suppress("unused")
    class Host : Base() {
        override fun key(): String = "host"
        fun pick(value: CharSequence?): String = "host-chars"
        fun pick(value: String?): String = "host-string"
        fun count(value: Int): String = "int"
        fun both(value: String?, other: Any?): String = "string-any"
        fun both(value: Any?, other: String?): String = "any-string"
        fun fails(): Nothing = throw IllegalStateException("host failure")

        class Nested
    }

    private val host = Host()

    @Test fun callPicksTheMostSpecificOverloadAcrossTheHierarchy() {
        assertEquals("host", host.callMethodOrNull("key"))
        assertEquals("host-string", host.callMethodOrNull("pick", "text"))
        assertEquals("host-chars", host.callMethodOrNull("pick", StringBuilder("text")))
        assertEquals("base-any", host.callMethodOrNull("pick", 1))
    }

    @Test fun callBoxesPrimitivesAndRejectsNullForThem() {
        assertEquals("int", host.callMethodOrNull("count", 3))
        assertNull(host.callMethodOrNull("count", null))
    }

    @Test fun callReturnsNullForMissingAmbiguousOrSuperPrivateMethods() {
        assertNull(host.callMethodOrNull("missing"))
        assertNull(host.callMethodOrNull("both", "a", "b"))
        assertNull(host.callMethodOrNull("hidden"))
        assertEquals("string-any", host.callMethodOrNull("both", "a", 1))
    }

    @Test fun callPropagatesTheMethodsOwnException() {
        val error = runCatching { host.callMethodOrNull("fails") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test fun classLoadingFallsBackToNestedClassNames() {
        val loader = javaClass.classLoader
        // Only the last separator is retried as `$`, as EzHookTool did.
        val name = Host.Nested::class.java.name
        val dotted = name.substring(0, name.lastIndexOf('$')) + "." + name.substringAfterLast('$')
        assertEquals(Host.Nested::class.java, loadHostClassOrNull(dotted, loader))
        assertNull(loadHostClassOrNull("com.example.Missing", loader))
    }
}
