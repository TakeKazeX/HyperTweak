package com.takekazex.hypertweak.presentation.icon

import android.graphics.Paint
import android.graphics.Typeface
import com.takekazex.hypertweak.settings.contract.MobileTypeTypography

/** Explicit variation axis overrides Xiaomi's bundled font's fixed wght axis as well. */
fun MobileTypeTypography.applyTo(paint: Paint) {
    if (!enabled) return
    val config = safe()
    paint.textSize *= config.scale
    paint.typeface = Typeface.create(paint.typeface, config.weight, false)
    paint.fontVariationSettings = "'wght' ${config.weight}"
}
