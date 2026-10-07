package com.takekazex.hypertweak.dock

import java.io.DataInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks compiled calls without initializing the Android/system_server singleton on the JVM. */
class DockHookWiringTest {
    @Test fun wmsCallbackCallsControllerRatherThanInvokingHookedPlatformMethod() {
        val owner = "com/takekazex/hypertweak/hook/rules/system/DockBackgroundHooker"
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("$owner.class"))
        val references = DataInputStream(stream).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt())
            input.readUnsignedShort(); input.readUnsignedShort()
            val pool = arrayOfNulls<Any>(input.readUnsignedShort())
            val methods = mutableListOf<Pair<Int, Int>>()
            var index = 1
            while (index < pool.size) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> pool[index] = input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> { input.readLong(); index++ }
                    7, 8, 16, 19, 20 -> pool[index] = input.readUnsignedShort()
                    9, 10, 11, 12, 17, 18 -> {
                        val pair = input.readUnsignedShort() to input.readUnsignedShort()
                        pool[index] = pair
                        if (tag == 10 || tag == 11) methods += pair
                    }
                    15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                    else -> error("Unknown classfile constant tag $tag")
                }
                index++
            }
            methods.map { (classIndex, signatureIndex) ->
                val className = pool[pool[classIndex] as Int] as String
                val signature = pool[signatureIndex] as Pair<*, *>
                className to (pool[signature.first as Int] as String)
            }
        }
        assertTrue("WMS callback must reach the Dock controller", owner to "prepareWindow" in references)
        assertFalse("Callback must never reinvoke hooked prepareSurfaces", "java/lang/reflect/Method" to "invoke" in references)
    }
}
