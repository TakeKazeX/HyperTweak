package com.takekazex.hypertweak.hook.rules.camera

import org.junit.Assert.*
import org.junit.Test

class CameraResolverTest {
    interface Capability { fun enabled(): Boolean }
    open class Provider : Capability { override fun enabled() = true }
    class Child : Provider()
    class Overloaded {
        fun read(): String = ""
        fun read(value: Int): Int = value
    }

    @Test fun `provider dispatch follows the live object hierarchy`() {
        val contract = Capability::class.java.getDeclaredMethod("enabled")
        val method = CameraResolver.findConcreteImplementation(Child(), contract)
        assertEquals(Provider::class.java, method?.declaringClass)
        assertNull(CameraResolver.findConcreteImplementation(Any(), contract))
    }

    @Test fun `an exact contract rejects a reused name with the wrong signature`() {
        assertNull(CameraResolver.resolveMethod("test", "contract", Overloaded::class.java, "read") {
            it.parameterCount == 0 && it.returnType == Integer.TYPE
        })
        assertNotNull(CameraResolver.resolveMethod("test", "contract", Overloaded::class.java, "read") {
            it.parameterCount == 0 && it.returnType == String::class.java
        })
    }

    @Test fun `a method name alone cannot choose between overloads`() {
        assertNull(CameraResolver.resolveMethod("test", "ambiguous", Overloaded::class.java, "read"))
    }
    @Test fun `compatible method lookup still selects the typed overload`() {
        val method = com.takekazex.hypertweak.hook.base.CompatibleMethodResolver.find(
            Overloaded::class.java, "read", parameterTypes = listOf(Integer.TYPE),
        )
        assertEquals(Integer.TYPE, method?.returnType)
        assertEquals(1, method?.parameterCount)
    }
}
