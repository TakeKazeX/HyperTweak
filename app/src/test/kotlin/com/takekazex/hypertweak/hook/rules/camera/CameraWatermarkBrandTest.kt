package com.takekazex.hypertweak.hook.rules.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the single-render decision of the custom watermark brand.
 *
 * REGRESSION HISTORY: the previous implementation composed the brand onto the RENDERED model
 * text of every WmModelView (`fs.m#o` after-hook prepend), guarded only by a `contains()` check
 * on the output. Whenever a template's format already carried the `@{logo}` token, or the
 * parse-time `m.c()` call had seeded the field before `J0` re-rendered it, the brand appeared
 * twice — stacked as two lines. The fix injects a leading `@{logo}` line into the view FORMAT
 * instead, so [CameraWatermarkBrand.formatWithLogoLine] must decline injection whenever the
 * stock substitution would already render the brand (bundled logo brand, existing token).
 */
class CameraWatermarkBrandTest {

    @Test
    fun `bundled logo brands detected case-insensitively`() {
        assertTrue(CameraWatermarkBrand.isBundledLogoBrand("XIAOMI"))
        assertTrue(CameraWatermarkBrand.isBundledLogoBrand("redmi"))
        assertTrue(CameraWatermarkBrand.isBundledLogoBrand("PoCo"))
        assertFalse(CameraWatermarkBrand.isBundledLogoBrand("XIAOMI "))
        assertFalse(CameraWatermarkBrand.isBundledLogoBrand("ACME"))
        assertFalse(CameraWatermarkBrand.isBundledLogoBrand("小米"))
        assertFalse(CameraWatermarkBrand.isBundledLogoBrand(""))
    }

    @Test
    fun `bundled logo brands never get a text line`() {
        assertNull(CameraWatermarkBrand.formatWithLogoLine("@{series}", "REDMI"))
        assertNull(CameraWatermarkBrand.formatWithLogoLine("@{series}", "xiaomi"))
    }

    @Test
    fun `formats already resolving the logo are left alone`() {
        assertNull(CameraWatermarkBrand.formatWithLogoLine("@{logo}\n@{series}", "ACME"))
        assertNull(CameraWatermarkBrand.formatWithLogoLine("@{logo} @{series}", "acme"))
        // A leading token from an earlier injection pass is still an existing token.
        assertNull(CameraWatermarkBrand.formatWithLogoLine("@{logo}\n@{series}", "ACME"))
    }

    @Test
    fun `blank brand and missing format decline injection`() {
        assertNull(CameraWatermarkBrand.formatWithLogoLine("@{series}", ""))
        assertNull(CameraWatermarkBrand.formatWithLogoLine("@{series}", "  "))
        assertNull(CameraWatermarkBrand.formatWithLogoLine(null, "ACME"))
    }

    @Test
    fun `custom brands get exactly one leading logo line`() {
        assertEquals(
            "@{logo}\n@{series}",
            CameraWatermarkBrand.formatWithLogoLine("@{series}", "ACME"),
        )
        assertEquals(
            "@{logo}\n@{series}",
            CameraWatermarkBrand.formatWithLogoLine("@{series}", "我的品牌"),
        )
        // An empty format still renders the brand line alone after substitution.
        assertEquals(
            "${CameraWatermarkBrand.LOGO_TOKEN}\n",
            CameraWatermarkBrand.formatWithLogoLine("", "ACME"),
        )
    }
}
