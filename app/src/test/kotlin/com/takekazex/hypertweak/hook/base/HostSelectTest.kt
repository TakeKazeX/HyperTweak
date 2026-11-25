package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class HostSelectTest {
    @Suppress("unused")
    private open class Base { open fun value(): Any = 0 }

    @Suppress("unused")
    private class Host(private val label: String) : Base() {
        private var count = 0
        private var other = 0
        fun update(value: Int) = Unit
        fun update(value: String) = Unit
        fun reset() = Unit
        override fun value(): String = label // javac/kotlinc also emit a bridge value(): Object
        constructor(label: String, count: Int) : this(label)
    }

    @Test fun exactlyOneMatchIsSelectedAndMadeAccessible() {
        val reset = HostSelect.method(Host::class.java) { name("reset"); noParams() }
        assertEquals("reset", reset?.name)
        assertTrue(reset!!.isAccessible)
        assertEquals(String::class.java, HostSelect.method(Host::class.java) { name("update"); params(String::class.java) }
            ?.parameterTypes?.single())
    }

    @Test fun ambiguityIsRejectedInsteadOfPickingTheFirst() {
        assertNull(HostSelect.method(Host::class.java) { name("update"); paramCount(1) })
        assertNull(HostSelect.field(Host::class.java) { type(Int::class.javaPrimitiveType) })
        assertEquals("count", HostSelect.field(Host::class.java) { name("count"); type(Int::class.javaPrimitiveType) }?.name)
    }

    @Test fun bridgeMethodsNeedExplicitOptIn() {
        val plain = HostSelect.method(Host::class.java) { name("value"); noParams() }
        assertEquals(String::class.java, plain?.returnType)
        assertNull(HostSelect.method(Host::class.java) { name("value"); noParams(); synthetic() })
    }

    @Test fun constructorsMatchByPartialParameters() {
        assertNotNull(HostSelect.constructor(Host::class.java) { params(String::class.java, null) })
        assertNull(HostSelect.constructor(Host::class.java) { paramCount(3) })
    }

    @Test fun keyedSelectionCachesMissesPerClass() {
        var evaluations = 0
        repeat(3) {
            assertNull(HostSelect.method(Host::class.java, "test.missing") {
                where { evaluations++; false }
            })
        }
        assertTrue(evaluations > 0)
        val afterFirst = evaluations
        HostSelect.method(Host::class.java, "test.missing") { where { evaluations++; false } }
        assertEquals(afterFirst, evaluations)
    }
}
