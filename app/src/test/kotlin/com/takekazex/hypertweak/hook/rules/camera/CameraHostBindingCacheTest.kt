package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.junit.Assert.*
import org.junit.Test

class CameraHostBindingCacheTest {
    class Config {
        fun master(): Boolean = true
        fun orders(): IntArray = intArrayOf(1)
        fun effects(): Map<String, String> = emptyMap()
    }
    class Facade {
        var config = Config()
        fun models(): Array<String> = arrayOf("model")
        fun brand(): String = "brand"
        companion object {
            @JvmField val INSTANCE = Facade()
            @JvmStatic fun lcc(): Boolean = true
        }
    }
    private val loader = javaClass.classLoader!!
    private fun stored() = CameraHostProfile.Binding(
        Facade::class.java, Facade::class.java.getDeclaredField("config"), Config::class.java,
        "models", "brand", "lcc", "master", listOf("orders"), "effects", null,
        Facade::class.java.getDeclaredField("INSTANCE"),
    ).descriptors()

    @Test fun `binding replay retains field and optional method contracts`() {
        val decoded = requireNotNull(CameraHostProfile.decodeBinding(stored(), loader))
        assertEquals(stored(), decoded.descriptors())
        assertTrue(decoded.configInstance() is Config)
    }

    @Test fun `same-owner wrong return type cannot replace a cached slot`() {
        assertNull(CameraHostProfile.decodeBinding(stored() + ("model" to "brand"), loader))
    }

    @Test fun `static singleton cannot replace the instance config field`() {
        val wrong = MethodDescriptorCodec.descriptor(Facade::class.java.getDeclaredField("INSTANCE"))
        assertNull(CameraHostProfile.decodeBinding(stored() + ("configField" to wrong), loader))
    }

    @Test fun `instance config field cannot replace a cached singleton`() {
        val wrong = MethodDescriptorCodec.descriptor(Facade::class.java.getDeclaredField("config"))
        assertNull(CameraHostProfile.decodeBinding(stored() + ("singleton" to wrong), loader))
    }

    @Test fun `truncated mode order graph is rejected`() {
        assertNull(CameraHostProfile.decodeBinding(stored() + ("orderCount" to "2"), loader))
    }
}
