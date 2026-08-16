package com.takekazex.hypertweak.hook.rules.systemui.glass

/**
 * Pure math behind the glass tuner, kept free of Android types so the JVM tests can cover it.
 *
 * The overridden SystemUI resources are:
 * - blend color arrays (`window_background_blend_colors[_bionics]`,
 *   `shade_blend_colors[_bionics]`), laid out as `[color, blendMode, ...]` pairs;
 * - max blur radius dimens, where the applied radius is `ratio × maxRadius`
 *   (`MiBlurCompat.blurRatio2Radius`), so scaling the max radius scales the actual blur;
 * - the dim scrim color `notification_control_center_blur_dim_color`.
 */

/** Scales the alpha channel of every color entry (even indices) in a blend array. */
internal fun scaleBlendArray(colors: IntArray, alpha: Float): IntArray {
    if (alpha >= 1f) return colors
    val scaled = colors.copyOf()
    for (i in scaled.indices step 2) {
        scaled[i] = scaleColorAlpha(scaled[i], alpha)
    }
    return scaled
}

/** Scales the alpha channel of [color], preserving RGB. */
internal fun scaleColorAlpha(color: Int, alpha: Float): Int {
    if (alpha >= 1f) return color
    val a = ((color ushr 24) * alpha).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

/**
 * Mixes the RGB channels of [color] toward white (>1.0) or black (<1.0), preserving alpha.
 * 1.0 returns the color unchanged. Used to lighten or darken the blend tint itself, e.g. the
 * deep `#5c292929` control-center backdrop or the `#52808080` shade blend.
 */
internal fun scaleColorLightness(color: Int, lightness: Float): Int {
    if (lightness == 1f) return color
    val a = (color ushr 24) and 0xFF
    val towardWhite = lightness > 1f
    val t = if (towardWhite) lightness - 1f else 1f - lightness
    fun mix(channel: Int): Int {
        val target = if (towardWhite) 255 else 0
        return (channel + (target - channel) * t).toInt().coerceIn(0, 255)
    }
    val r = (color ushr 16) and 0xFF
    val g = (color ushr 8) and 0xFF
    val b = color and 0xFF
    return (a shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
}

/** Applies alpha scaling and lightness mixing to every color entry (even indices) in a blend array. */
internal fun scaleBlendArrayColors(colors: IntArray, alpha: Float, lightness: Float): IntArray {
    if (alpha >= 1f && lightness == 1f) return colors
    val scaled = colors.copyOf()
    for (i in scaled.indices step 2) {
        scaled[i] = scaleColorLightness(scaleColorAlpha(scaled[i], alpha), lightness)
    }
    return scaled
}

/** Scales a max blur radius; the actual applied radius is `ratio × maxRadius`. */
internal fun scaleRadius(radius: Int, scale: Float): Int = (radius.toFloat() * scale).toInt()

/**
 * Indices of the 42-float glass shader params that control how "solid" the glass card looks,
 * matching the layout used by `KeyguardEditorHelper` / `GlassToken`:
 * - 7  = blend.darker (darkening)
 * - 14 = inner.color alpha (white tint)
 * - 15 = inner.colorWhite
 * - 23 = reflect.lighten, 24 = reflect.strength (highlights)
 * - 29 = directionalLight.colorWhite
 * Everything else (luminance, saturation, edge shape, refraction, blur-bg colors) is left alone.
 */
internal val GLASS_TRANSPARENCY_INDICES = intArrayOf(7, 14, 15, 23, 24, 29)

/**
 * Glass "tone" params — the base cast the shader applies on top of the blurred backdrop, which
 * survives even at zero transparency/opacity and reads as a grey-ish, heavy look:
 * - 4  = luminanceAmount (how much luminance is mixed in)
 * - 6  = brightness (negative values darken)
 * - 33 = blurBg.saturation (the blurred backdrop's saturation; 2.0 on the OS4 baseline)
 * Lower tone = lighter, less saturated, more see-through.
 */
internal val GLASS_TONE_INDICES = intArrayOf(4, 6, 33)

/**
 * Scales the glass-card shader params: [opacity] weakens the darkening/tint/highlight layers,
 * [tone] weakens the base luminance/brightness/saturation cast. All-zero arrays (the "clear
 * glass" path) and arrays shorter than the last scaled index are returned unchanged so a reset
 * really clears the effect.
 */
internal fun scaleGlassParams(params: FloatArray, opacity: Float, tone: Float): FloatArray {
    if (opacity >= 1f && tone == 1f) return params
    if (params.size <= GLASS_TONE_INDICES.last()) return params
    if (params.all { it == 0f }) return params
    val scaled = params.copyOf()
    for (i in GLASS_TRANSPARENCY_INDICES) {
        scaled[i] = params[i] * opacity
    }
    for (i in GLASS_TONE_INDICES) {
        scaled[i] = params[i] * tone
    }
    return scaled
}
