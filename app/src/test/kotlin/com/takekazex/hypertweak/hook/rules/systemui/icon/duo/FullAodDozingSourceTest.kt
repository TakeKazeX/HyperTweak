package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class FullAodDozingSourceTest {
    interface Api
    class Manager : Api { @JvmField var isDozing = false }
    class Registry {
        companion object {
            var manager: Any? = null
            @JvmStatic fun getImpl(api: Class<*>): Any? {
                assertEquals(Api::class.java, api)
                return manager
            }
        }
    }
    @After fun clear() { Registry.manager = null }
    private fun source() = FullAodDozingSource(Manager::class.java, Registry::class.java, Api::class.java)

    @Test fun `cold registry recovers and reads the live host flag`() {
        val source = source()
        assertNull(source.read())
        val manager = Manager()
        Registry.manager = manager
        assertEquals(false, source.read())
        manager.isDozing = true
        assertEquals(true, source.read())
        manager.isDozing = false
        assertEquals(false, source.read())
    }

    @Test fun `constructor recovery can replace an already captured manager`() {
        val source = source()
        Registry.manager = Manager().apply { isDozing = true }
        assertEquals(true, source.read())
        source.bind(Manager())
        assertEquals(false, source.read())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong registry implementation fails closed`() {
        Registry.manager = Any()
        source().read()
    }
}
