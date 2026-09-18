package com.takekazex.hypertweak.hook.rules.thememanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the DRM resolution contract used by [ThemeManagerRightsCheckHooker] and
 * [com.takekazex.hypertweak.hook.rules.system.ThemeDrmRevalidationHooker].
 *
 * Both hookers decide what to bypass from a reflected shape, so a wrong verdict — hooking an
 * unrelated `isLegal` or answering with the wrong enum constant — would silently disable theme
 * verification elsewhere instead of failing loudly. The fixtures below mimic MIUI's
 * `DrmManager` shape: several `isLegal` overloads plus unrelated members.
 */
class ThemeDrmSupportTest {

    enum class FakeDrmResult { DRM_SUCCESS, DRM_ERROR_ASSET_NOT_MATCH, DRM_ERROR_UNKNOWN }

    enum class UnrelatedResult { DRM_SUCCESS, OTHER }

    @Suppress("unused")
    private class DrmManagerFixture {
        companion object {
            @JvmStatic
            fun isLegal(context: Any, content: Any, rights: Any): FakeDrmResult =
                FakeDrmResult.DRM_ERROR_ASSET_NOT_MATCH

            @JvmStatic
            fun isLegal(context: Any, hash: String, rights: Any): FakeDrmResult =
                FakeDrmResult.DRM_ERROR_ASSET_NOT_MATCH

            /** Private DRM overload MIUI funnels the public ones into. */
            @JvmStatic
            fun isLegal(context: Any, rights: Any): FakeDrmResult =
                FakeDrmResult.DRM_ERROR_ASSET_NOT_MATCH

            /** Same name, unrelated return type: must never be treated as a DRM verdict. */
            @JvmStatic
            fun isLegal(context: Any): Boolean = false

            @JvmStatic
            fun isSupportAd(context: Any): Boolean = false

            /** DRM-shaped return but not a verdict call. */
            @JvmStatic
            fun getMorePreciseDrmResult(first: Any, second: Any): FakeDrmResult = first as FakeDrmResult
        }
    }

    /**
     * A manager whose only `isLegal` is an instance method. `miui.drm.DrmManager` declares its
     * verdicts static, so selecting this shape would install a hook that never runs.
     */
    @Suppress("unused")
    private class InstanceOnlyFixture {
        fun isLegal(content: Any, rights: Any): FakeDrmResult = FakeDrmResult.DRM_SUCCESS
    }

    @Test
    fun `drm success constant is read from the enum by name`() {
        assertSame(
            FakeDrmResult.DRM_SUCCESS,
            ThemeDrmSupport.drmSuccessConstant(FakeDrmResult::class.java)
        )
    }

    @Test
    fun `non enum and unrelated enum types yield no verdict`() {
        assertNull(ThemeDrmSupport.drmSuccessConstant(String::class.java))
        assertNull(ThemeDrmSupport.drmSuccessConstant(null))

        // An enum without DRM_SUCCESS is not MIUI's DRM result; answering with one of its
        // constants would be a type error at runtime.
        assertNull(ThemeDrmSupport.drmSuccessConstant(EmptyResult::class.java))
        assertFalse(ThemeDrmSupport.isDrmResult(EmptyResult::class.java))
        assertFalse(ThemeDrmSupport.isDrmResult(null))
        assertTrue(ThemeDrmSupport.isDrmResult(FakeDrmResult::class.java))
    }

    @Test
    fun `only static DRM returning isLegal overloads are selected`() {
        val selected = ThemeDrmSupport.isLegalOverloads(DrmManagerFixture::class.java)

        assertEquals(listOf(2, 3, 3), selected.map { it.parameterCount }.sorted())
        assertTrue(selected.all { it.name == "isLegal" })
        assertTrue(selected.all { ThemeDrmSupport.isDrmResult(it.returnType) })
        assertTrue(selected.all { java.lang.reflect.Modifier.isStatic(it.modifiers) })
        // The boolean overload stays out of the verdict set, and a manager whose only isLegal is
        // an instance method contributes nothing.
        assertTrue(selected.none { it.parameterCount == 1 })
        assertTrue(selected.none { it.returnType == java.lang.Boolean.TYPE })
        assertTrue(ThemeDrmSupport.isLegalOverloads(InstanceOnlyFixture::class.java).isEmpty())
    }

    @Test
    fun `verdict carrier is available whenever an overload is selected`() {
        val selected = ThemeDrmSupport.isLegalOverloads(DrmManagerFixture::class.java)

        // The system hooker skips installation when this is null, so a selected overload must
        // always carry the enum constant it answers with.
        assertNotNullValue(ThemeDrmSupport.drmSuccessConstant(selected.firstOrNull()?.returnType))
    }

    private fun assertNotNullValue(value: Any?) {
        assertTrue("expected DRM_SUCCESS constant, got null", value != null)
    }

    private enum class EmptyResult { SOMETHING_ELSE }
}
