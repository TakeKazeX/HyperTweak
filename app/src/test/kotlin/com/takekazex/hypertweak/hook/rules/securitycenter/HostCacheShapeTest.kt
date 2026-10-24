package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.rules.aicr.AicrDefaultBrowserCache
import org.junit.Assert.assertNull
import org.junit.Test

class HostCacheShapeTest {
    class Unrelated {
        companion object {
            @JvmStatic fun action(first: String, second: String, third: String): String = first
            @JvmStatic fun battery(): Boolean = true
        }
        fun confirm(accepted: Boolean): Boolean = accepted
    }
    private val loader = javaClass.classLoader!!

    @Test fun `AICR rejects a loadable unrelated method in the action slot`() {
        val method = Unrelated::class.java.getDeclaredMethod("action", String::class.java, String::class.java, String::class.java)
        assertNull(AicrDefaultBrowserCache.decode(mapOf("target.action" to MethodDescriptorCodec.descriptor(method)), loader))
    }

    @Test fun `battery readers reject a loadable method with the wrong result type`() {
        val method = Unrelated::class.java.getDeclaredMethod("battery")
        assertNull(MoreBatteryInfoCache.decode(mapOf("full_capacity" to MethodDescriptorCodec.descriptor(method)), loader))
    }

    @Test fun `permission confirmation rejects an unrelated boolean-returning method`() {
        val method = Unrelated::class.java.getDeclaredMethod("confirm", Boolean::class.javaPrimitiveType)
        assertNull(WarningCountdownCache.decode(mapOf("target" to MethodDescriptorCodec.descriptor(method)), loader))
    }
}
