package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.securitycenter.SettingsWriterResolver.select
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The power-save override hook installs nothing unless this selection matches the framework's real
 * Settings writers. The first version filtered on `Void.TYPE` while `Settings$Secure.putInt` returns
 * **boolean**, so it resolved nothing, logged `putInt not found`, and silently disabled every
 * feature. The fixtures below mirror the shapes `javap` reports for `android.provider.Settings$Secure`
 * and `Settings$System` on API 36 — including being **static**, which is a checked part of the rule.
 */
@Suppress("unused")
private object FrameworkWriters {
    // Settings$Secure.putInt / Settings$System.putInt — boolean in AOSP.
    @JvmStatic
    fun putInt(resolver: Any, name: String, value: Int): Boolean = true

    // Settings$Secure.putString / Settings$System.putString — also boolean.
    @JvmStatic
    fun putString(resolver: Any, name: String, value: String): Boolean = true

    // An incompatible result/value shape must never receive the Boolean interception result.
    @JvmStatic
    fun putInt(resolver: Any, name: String, value: Boolean) {}

    @JvmStatic
    fun putString(resolver: Any, name: String, value: Int): Boolean = true

    @JvmStatic
    fun putInt(resolver: Any, name: String, value: String): Int = 1

    // Must not be selected.
    @JvmStatic
    fun putInt(resolver: Any, name: String): Boolean = true

    @JvmStatic
    fun putInt(resolver: Any, name: String, value: Long): Boolean = true

    @JvmStatic
    fun putLong(resolver: Any, name: String, value: Long): Boolean = true

    @JvmStatic
    fun putFloat(resolver: Any, name: String, value: Float): Boolean = true

    @JvmStatic
    fun getInt(resolver: Any, name: String, value: Int): Int = value
}

@Suppress("unused")
private class InstanceWriter {
    fun putInt(resolver: Any, name: String, value: Int): Boolean = true
}

class SettingsWriterResolverTest {

    private fun select(owner: Class<*>): List<Method> =
        select(owner.declaredMethods, Any::class.java)

    private fun signatureOf(method: Method): String = buildString {
        append(method.name)
        append('(')
        append(method.parameterTypes.joinToString(",") { it.simpleName })
        append("):")
        append(method.returnType.simpleName)
    }

    @Test
    fun `selects the boolean-returning int and string writers`() {
        // The regression: a Void-only filter returns an empty list here.
        assertEquals(
            listOf(
                "putInt(Object,String,int):boolean",
                "putString(Object,String,String):boolean",
            ),
            select(FrameworkWriters::class.java).map(::signatureOf).sorted(),
        )
    }

    @Test
    fun `ignores unrelated settings methods`() {
        val selected = select(FrameworkWriters::class.java).map(::signatureOf).toSet()
        assertTrue(
            "2-arg putInt must not be selected",
            selected.none { it == "putInt(Object,String):boolean" },
        )
        assertTrue("Long writer must not be selected", selected.none { it.startsWith("putLong") })
        assertTrue("Float writer must not be selected", selected.none { it.startsWith("putFloat") })
        assertTrue("getter must not be selected", selected.none { it.startsWith("getInt") })
        assertTrue(
            "Long-valued putInt must not be selected",
            selected.none { it == "putInt(Object,String,long):boolean" },
        )
    }

    @Test
    fun `ignores instance writers`() {
        // Instance methods cannot be the static Settings entry points.
        assertEquals(emptyList<Method>(), select(InstanceWriter::class.java))
    }

    @Test
    fun `rejects a mismatched resolver parameter type`() {
        // Guards the parameter-shape half of the rule: a writer whose first argument is not the
        // platform ContentResolver must not be hooked.
        assertEquals(
            emptyList<Method>(),
            SettingsWriterResolver.select(FrameworkWriters::class.java.declaredMethods, String::class.java),
        )
    }
}
