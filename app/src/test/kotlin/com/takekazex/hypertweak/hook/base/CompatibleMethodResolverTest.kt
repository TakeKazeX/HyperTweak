package com.takekazex.hypertweak.hook.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompatibleMethodResolverTest {
    private class Subject {
        fun boxed(value: Int): String = value.toString()
        fun ambiguous(value: Number): String = value.toString()
        fun ambiguous(value: Int): String = value.toString()
    }

    @Test fun resolvesPrimitiveFromBoxedRuntimeType() {
        val method = CompatibleMethodResolver.find(
            Subject::class.java, "boxed", String::class.java, listOf(Int::class.java)
        )
        assertEquals(Int::class.javaPrimitiveType, method?.parameterTypes?.single())
    }

    @Test fun rejectsAmbiguousCompatibleOverloads() {
        assertNull(CompatibleMethodResolver.find(
            Subject::class.java, "ambiguous", String::class.java, listOf(Any::class.java)
        ))
    }
}
