package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class DexKitCacheIdentityTest {
    @Test fun `APK replacement invalidates unrequested feature entries too`() {
        val old = DexKitCacheIdentity(5, 100, "old-digest")
        val next = DexKitCacheIdentity(5, 100, "new-digest")
        val properties = Properties().apply {
            old.stamp(this)
            setProperty("feature_one", "OldOwner")
            setProperty("feature_two", "OtherOldOwner")
        }
        assertFalse(next.prepare(properties))
        properties.setProperty("feature_one", "NewOwner")
        next.stamp(properties)
        val reloaded = Properties().apply { load(propertiesAsText(properties).reader()) }
        assertTrue(next.prepare(reloaded))
        assertEquals("NewOwner", reloaded.getProperty("feature_one"))
        assertNull(reloaded.getProperty("feature_two"))
    }

    @Test fun `a cache created before schema migration cannot retain mixed APK entries`() {
        val identity = DexKitCacheIdentity(10, 20, "same-apk")
        val properties = Properties().apply {
            identity.stamp(this)
            remove("cache_schema")
            setProperty("camera", "StaleButLoadable")
        }
        assertFalse(identity.prepare(properties))
        assertTrue(properties.isEmpty())
    }

    @Test fun `an empty resolved cache still preserves invalidation across reload`() {
        val identity = DexKitCacheIdentity(10, 20, "apk")
        val properties = Properties().apply { identity.stamp(this); setProperty("feature", "WrongOwner") }
        assertTrue(identity.prepare(properties))
        properties.remove("feature")
        identity.stamp(properties)
        val reloaded = Properties().apply { load(propertiesAsText(properties).reader()) }
        assertTrue(identity.prepare(reloaded))
        assertNull(reloaded.getProperty("feature"))
    }

    private fun propertiesAsText(properties: Properties): String = java.io.StringWriter().also {
        properties.store(it, null)
    }.toString()
}
