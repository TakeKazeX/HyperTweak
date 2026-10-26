package com.takekazex.hypertweak.hook.rules.securitycenter.battery

import android.content.Context
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayList

class PowerRankingCacheTest {
    class Host {
        companion object {
            @JvmStatic fun card(context: Context, count: Int, first: Boolean, second: Boolean): List<Any> = emptyList()
        }
        fun instanceCard(context: Context, count: Int, first: Boolean, second: Boolean): List<Any> = emptyList()
    }
    class Row {
        @JvmField var label = ""
        @JvmField var packageName = ""
        @JvmField var power = 0.0
        @JvmField var uid = 0
        @JvmField var kind = 0
    }
    private val loader = javaClass.classLoader!!
    private fun descriptor(name: String) = MethodDescriptorCodec.descriptor(Host::class.java.getDeclaredMethod(
        name, Context::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType))

    @Test fun `list allocation before the row allocation cannot hide the real card row`() {
        val targets = PowerRankingCache.decode(mapOf(
            "card" to descriptor("card"),
            "row.class.0" to ArrayList::class.java.name,
            "row.class.1" to Row::class.java.name,
        ), loader)
        assertNotNull(targets)
        assertEquals(Row::class.java, targets!!.rowClass)
    }

    @Test fun `an instance method cannot masquerade as the static card helper`() {
        assertNull(PowerRankingCache.decode(mapOf("card" to descriptor("instanceCard")), loader))
    }

    @Test fun `a missing row class rejects a damaged cached graph`() {
        assertNull(PowerRankingCache.decode(mapOf(
            "card" to descriptor("card"), "row.class.0" to "no.such.CardRow"), loader))
    }
}
