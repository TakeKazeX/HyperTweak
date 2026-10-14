package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.util.Locale

/** Built-in 20-unit stacked art has a 1.35-unit row gap; keep at least 0.45 at maximum. */
object SignalWeight {
    private val path = Regex("""<path\b[^<>]*\bid="signal_[12]_[1-4]"[^<>]*/>""")
    fun normalize(value: Float): Float = value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f

    fun apply(source: String, value: Float): String {
        val weight = normalize(value)
        if (weight == 0f) return source
        val width = "%.5f".format(Locale.US, weight * 0.9f)
        return path.replace(source) { match ->
            // Stroke expands the original filled contour, preserving corners and level opacity.
            match.value.dropLast(2) + " stroke=\"white\" stroke-width=\"$width\" stroke-linejoin=\"round\"/>"
        }
    }
}
