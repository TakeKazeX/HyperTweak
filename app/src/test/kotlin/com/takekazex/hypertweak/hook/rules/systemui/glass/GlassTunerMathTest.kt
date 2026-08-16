package com.takekazex.hypertweak.hook.rules.systemui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GlassTunerMathTest {

    @Test
    fun scaleColorAlpha_halvesAlphaPreservingRgb() {
        // 0x59 (89) alpha over #555555; halving gives 44 (0x2C).
        assertEquals(0x2C555555.toInt(), scaleColorAlpha(0x59555555.toInt(), 0.5f))
    }

    @Test
    fun scaleColorAlpha_zeroAlphaKeepsRgb() {
        assertEquals(0x00808080, scaleColorAlpha(0x52808080.toInt(), 0f))
    }

    @Test
    fun scaleColorAlpha_fullAlphaReturnsOriginalValue() {
        val color = 0x52808080.toInt()
        assertEquals(color, scaleColorAlpha(color, 1f))
        assertEquals(color, scaleColorAlpha(color, 2f))
    }

    @Test
    fun scaleColorAlpha_neverExceedsSourceAlpha() {
        // alpha < 1 can never push a color's alpha above its source value.
        assertEquals(0x7F808080.toInt(), scaleColorAlpha(0x80808080.toInt(), 0.9999f))
    }

    @Test
    fun scaleBlendArray_scalesOnlyColorEntries() {
        // The real arrays are [color, blendMode, color, blendMode, ...]; modes must stay intact.
        val original = intArrayOf(0x52808080.toInt(), 28, 0x73212121.toInt(), 120, 0x247c7c7c.toInt(), 3)
        val scaled = scaleBlendArray(original, 0.5f)
        assertEquals(0x29808080.toInt(), scaled[0])
        assertEquals(28, scaled[1])
        assertEquals(0x39212121.toInt(), scaled[2])
        assertEquals(120, scaled[3])
        assertEquals(0x127C7C7C.toInt(), scaled[4])
        assertEquals(3, scaled[5])
        // Original untouched.
        assertEquals(0x52808080.toInt(), original[0])
    }

    @Test
    fun scaleBlendArray_fullAlphaReturnsSameInstance() {
        val colors = intArrayOf(0x52808080.toInt(), 28)
        assertSame(colors, scaleBlendArray(colors, 1f))
    }

    @Test
    fun scaleRadius_linear() {
        assertEquals(100, scaleRadius(100, 1f))
        assertEquals(50, scaleRadius(100, 0.5f))
        assertEquals(72, scaleRadius(72.72998f.toInt(), 1f)) // original 72.72998dp -> 72dp px scaled
        assertEquals(0, scaleRadius(100, 0f))
        // 72.72998dp truncates to 72px before scaling (getDimensionPixelSize returns an Int).
        assertEquals(144, scaleRadius(72.72998f.toInt(), 2f))
    }

    @Test
    fun scaleGlassParams_scalesOnlyTransparencyIndices() {
        val params = FloatArray(42) { 0f }
        params[4] = 0.24f // luminanceAmount, must stay
        params[5] = 1.4f // saturation, must stay
        params[7] = 0.3f // darker
        params[14] = 0.1f // inner.color alpha
        params[15] = 0.2f // colorWhite
        params[23] = 1.2f // reflect.lighten
        params[24] = 1.0f // reflect.strength
        params[29] = 0.7f // directionalLight.colorWhite
        params[32] = 4.0f // refract.ior, must stay

        val scaled = scaleGlassParams(params, 0.5f, 1f)
        assertEquals(0.15f, scaled[7], 1e-6f)
        assertEquals(0.05f, scaled[14], 1e-6f)
        assertEquals(0.1f, scaled[15], 1e-6f)
        assertEquals(0.6f, scaled[23], 1e-6f)
        assertEquals(0.5f, scaled[24], 1e-6f)
        assertEquals(0.35f, scaled[29], 1e-6f)
        assertEquals(0.24f, scaled[4], 1e-6f)
        assertEquals(1.4f, scaled[5], 1e-6f)
        assertEquals(4.0f, scaled[32], 1e-6f)
        // Original untouched.
        assertEquals(0.3f, params[7], 1e-6f)
    }

    @Test
    fun scaleGlassParams_fullOpacityReturnsSameInstance() {
        val params = FloatArray(42) { 0f }
        assertSame(params, scaleGlassParams(params, 1f, 1f))
        assertSame(params, scaleGlassParams(params, 2f, 1f))
    }

    @Test
    fun scaleGlassParams_zeroArrayUntouched() {
        val params = FloatArray(42) { 0f }
        assertSame(params, scaleGlassParams(params, 0.5f, 1f))
    }

    @Test
    fun scaleGlassParams_shortArrayUntouched() {
        val params = floatArrayOf(1f, 2f, 3f)
        assertSame(params, scaleGlassParams(params, 0.5f, 1f))
    }

    @Test
    fun scaleColorLightness_mixesTowardWhite() {
        // #5c292929 at 150%: alpha preserved, rgb mixes 50% toward white.
        val scaled = scaleColorLightness(0x5C292929.toInt(), 1.5f)
        assertEquals(0x5C, (scaled ushr 24) and 0xFF) // alpha preserved
        // 41 + (255-41)*0.5 = 148 = 0x94
        assertEquals(0x94, (scaled ushr 16) and 0xFF)
        assertEquals(0x94, (scaled ushr 8) and 0xFF)
        assertEquals(0x94, scaled and 0xFF)
    }

    @Test
    fun scaleColorLightness_mixesTowardBlack() {
        // #52808080 at 50% -> channels halve toward black.
        val scaled = scaleColorLightness(0x52808080.toInt(), 0.5f)
        assertEquals(0x52, (scaled ushr 24) and 0xFF)
        assertEquals(0x40, (scaled ushr 16) and 0xFF)
        assertEquals(0x40, scaled and 0xFF)
    }

    @Test
    fun scaleColorLightness_fullReturnsOriginalValue() {
        val color = 0x5C292929.toInt()
        assertEquals(color, scaleColorLightness(color, 1f))
    }

    @Test
    fun scaleBlendArrayColors_appliesAlphaAndLightness() {
        val original = intArrayOf(0x5C292929.toInt(), 100, 0x61FFFFFF.toInt(), 106)
        val scaled = scaleBlendArrayColors(original, 0.5f, 1.25f)
        // alpha 0x5C * 0.5 = 46 = 0x2E; rgb 0x29 -> 41 + (255-41)*0.25 = 94 = 0x5E
        assertEquals(0x2E5E5E5E.toInt(), scaled[0])
        assertEquals(100, scaled[1])
        // alpha 0x61 * 0.5 = 48 = 0x30; rgb 0xFF stays 0xFF
        assertEquals(0x30FFFFFF.toInt(), scaled[2])
        assertEquals(106, scaled[3])
        // Original untouched.
        assertEquals(0x5C292929.toInt(), original[0])
    }

    @Test
    fun scaleBlendArrayColors_defaultsReturnSameInstance() {
        val colors = intArrayOf(0x52808080.toInt(), 28)
        assertSame(colors, scaleBlendArrayColors(colors, 1f, 1f))
    }

    @Test
    fun scaleGlassParams_scalesToneIndices() {
        val params = FloatArray(42) { 0f }
        params[4] = 0.24f // luminanceAmount
        params[6] = -0.02f // brightness (negative darkens)
        params[33] = 2.0f // blurBg.saturation
        params[7] = 0.3f // darker, not part of tone

        val scaled = scaleGlassParams(params, 1f, 0.5f)
        assertEquals(0.12f, scaled[4], 1e-6f)
        assertEquals(-0.01f, scaled[6], 1e-6f)
        assertEquals(1.0f, scaled[33], 1e-6f)
        // Tone scaling must not touch the transparency group.
        assertEquals(0.3f, scaled[7], 1e-6f)
    }

    @Test
    fun scaleGlassParams_bothScaleTogether() {
        val params = FloatArray(42) { 0f }
        params[7] = 0.3f
        params[4] = 0.24f
        val scaled = scaleGlassParams(params, 0.5f, 0.5f)
        assertEquals(0.15f, scaled[7], 1e-6f)
        assertEquals(0.12f, scaled[4], 1e-6f)
    }

    @Test
    fun scaleGlassParams_defaultsReturnSameInstance() {
        val params = FloatArray(42) { 0f }
        assertSame(params, scaleGlassParams(params, 1f, 1f))
    }
}
