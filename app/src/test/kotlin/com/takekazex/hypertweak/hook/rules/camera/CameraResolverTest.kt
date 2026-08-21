package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Unit tests for the version-generic resolution layers of [CameraResolver].
 *
 * These verify the pure-resolution mechanics (candidate ordering, semantic validation,
 * name-candidate method lookup, structural factory recognition) against the Java fixtures in
 * [CameraFixtures]. The per-build camera class names themselves can only be verified
 * on-device / against dex tables, not in JVM tests.
 */
class CameraResolverTest {

    private val loader = CameraResolverTest::class.java.classLoader!!

    private val ctx = CameraResolver.Ctx(loader, null)

    @Test
    fun `candidates resolve in order and skip repurposed names`() {
        val resolved = CameraResolver.resolveClass(
            scope = "test", key = "fixture", ctx = ctx,
            candidates = listOf(
                CameraFixtures.Repurposed::class.java.name,
                CameraFixtures.Factory460::class.java.name,
            ),
            validate = { c -> c.declaredMethods.any { it.name == "q" } },
        )
        // Repurposed exists but fails the shape check; the next candidate is used.
        assertEquals(CameraFixtures.Factory460::class.java.name, resolved?.name)
    }

    @Test
    fun `a repurposed name alone is rejected`() {
        val resolved = CameraResolver.resolveClass(
            scope = "test", key = "fixture", ctx = ctx,
            candidates = listOf(CameraFixtures.Repurposed::class.java.name),
            validate = { c -> c.declaredMethods.any { it.name == "q" } },
        )
        assertNull(resolved)
    }

    @Test
    fun `method resolution falls back across renamed method candidates`() {
        val newShape = CameraResolver.resolveMethod(
            scope = "test", key = "factory",
            clazz = CameraFixtures.Factory510::class.java,
            names = listOf("G0", "q"),
            shape = { it.parameterTypes.isEmpty() && Modifier.isStatic(it.modifiers) },
        )
        assertNotNull(newShape)
        assertEquals("G0", newShape!!.name)

        val oldShape = CameraResolver.resolveMethod(
            scope = "test", key = "factory",
            clazz = CameraFixtures.Factory460::class.java,
            names = listOf("G0", "q"),
            shape = { it.parameterTypes.isEmpty() && Modifier.isStatic(it.modifiers) },
        )
        assertNotNull(oldShape)
        assertEquals("q", oldShape!!.name)
    }

    @Test
    fun `structural factory recognition is name independent`() {
        // 510 renamed q -> G0; the shape rule (static zero-arg returning the field-b type) finds it.
        val newFactory = CameraResolver.findFactoryMethod(CameraFixtures.Factory510::class.java)
        assertNotNull(newFactory)
        assertEquals("G0", newFactory!!.name)

        val oldFactory = CameraResolver.findFactoryMethod(CameraFixtures.Factory460::class.java)
        assertNotNull(oldFactory)
        assertEquals("q", oldFactory!!.name)

        // A class whose factory method vanished is NOT matched by the structural rule.
        assertNull(CameraResolver.findFactoryMethod(CameraFixtures.FactoryBroken::class.java))
    }

    @Test
    fun `shape helpers detect boolean getters and static zero-arg methods`() {
        assertTrue(CameraResolver.hasBooleanMethod(BooleanHolder::class.java, listOf("s")))
        assertTrue(!CameraResolver.hasBooleanMethod(BooleanHolder::class.java, listOf("t")))
        assertTrue(CameraResolver.hasStaticZeroArgMethod(CameraFixtures.Factory460::class.java, listOf("q")))
    }

    /** Provider-gate fixture mirroring the LCC tint-color gate (boolean zero-arg `s` vs int `i`). */
    class BooleanHolder {
        fun s(): Boolean = true
        fun t(): Int = 1
    }

    @Test
    fun `CompatibleMethodResolver still matches typed overloads uniquely`() {
        val method = CompatibleMethodResolver.find(
            CameraFixtures.Factory460::class.java, "q",
            parameterTypes = emptyList(),
        )
        assertNotNull(method)
    }
}