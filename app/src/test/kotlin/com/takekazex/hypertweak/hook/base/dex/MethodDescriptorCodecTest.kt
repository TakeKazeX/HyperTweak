package com.takekazex.hypertweak.hook.base.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MethodDescriptorCodecTest {
    private class Fields { @Suppress("unused") private var array: IntArray? = null }
    private val loader = MethodDescriptorCodecTest::class.java.classLoader!!

    @Test fun `field replay validates declared owner name and exact array type`() {
        val field = Fields::class.java.getDeclaredField("array")
        val descriptor = MethodDescriptorCodec.descriptor(field)
        assertEquals(field, MethodDescriptorCodec.field(descriptor, loader))
        assertNull(MethodDescriptorCodec.field(descriptor.replace(":[I", ":I"), loader))
        assertNull(MethodDescriptorCodec.field(descriptor + "garbage", loader))
        assertNull(MethodDescriptorCodec.field("not a field", loader))
    }

    @Test
    fun `round trips instance method`() {
        val method = String::class.java.getDeclaredMethod("substring", Int::class.javaPrimitiveType!!)
        val descriptor = MethodDescriptorCodec.descriptor(method)

        assertEquals("Ljava/lang/String;->substring(I)Ljava/lang/String;", descriptor)
        assertEquals(method, MethodDescriptorCodec.method(descriptor, loader))
    }

    @Test
    fun `round trips array parameters and return type`() {
        val method = java.util.Arrays::class.java.getDeclaredMethod(
            "copyOf",
            IntArray::class.java,
            Int::class.javaPrimitiveType
        )
        val descriptor = MethodDescriptorCodec.descriptor(method)

        assertEquals("[I", MethodDescriptorCodec.typeDescriptor(IntArray::class.java))
        assertEquals(method, MethodDescriptorCodec.method(descriptor, loader))
    }

    @Test
    fun `round trips void return and object parameter`() {
        val method = StringBuilder::class.java.getDeclaredMethod(
            "setLength",
            Int::class.javaPrimitiveType
        )
        val descriptor = MethodDescriptorCodec.descriptor(method)
        val equality = Any::class.java.getDeclaredMethod("equals", Any::class.java)

        assertEquals("Ljava/lang/StringBuilder;->setLength(I)V", descriptor)
        assertEquals(method, MethodDescriptorCodec.method(descriptor, loader))
        assertEquals(equality, MethodDescriptorCodec.method(MethodDescriptorCodec.descriptor(equality), loader))
    }

    @Test
    fun `rejects malformed descriptors`() {
        assertNull(MethodDescriptorCodec.method("", loader))
        assertNull(MethodDescriptorCodec.method("Ljava/lang/String;", loader))
        assertNull(MethodDescriptorCodec.method("Ljava/lang/String;->substring", loader))
        assertNull(MethodDescriptorCodec.method("Ljava/lang/String;->substring(I", loader))
        assertNull(MethodDescriptorCodec.method("Ldoes/not/Exist;->x()V", loader))
        assertNull(MethodDescriptorCodec.method("Ljava/lang/String;->doesNotExist()V", loader))
    }

    @Test
    fun `rejects mismatched return type`() {
        val descriptor = "Ljava/lang/String;->substring(I)I"
        assertNull(MethodDescriptorCodec.method(descriptor, loader))
        assertNotNull(
            MethodDescriptorCodec.method("Ljava/lang/String;->substring(I)Ljava/lang/String;", loader)
        )
    }
    @Test fun `constructor replay rejects wrong return and malformed parameter types`() {
        val constructor = StringBuilder::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        val descriptor = MethodDescriptorCodec.descriptor(constructor)
        assertEquals(constructor, MethodDescriptorCodec.constructor(descriptor, loader))
        assertNull(MethodDescriptorCodec.constructor(descriptor.dropLast(1) + "I", loader))
        assertNull(MethodDescriptorCodec.constructor(descriptor.replace("(I)", "(V)"), loader))
        assertNull(MethodDescriptorCodec.constructor(descriptor + "junk", loader))
        assertNull(MethodDescriptorCodec.method(descriptor, loader))
    }

}
