package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlin.math.ceil

/**
 * Renders network-type text as a white alpha mask for [HostIconBridge]. It never changes a
 * TextView or the global status-bar typeface; the font settings apply only to this bitmap.
 */
object MobileTypeRenderer {
    private const val MAX_CACHE_ENTRIES = 32

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {}

    fun render(
        output: MobileTypeOutput,
        config: MobileTypeConfig,
        iconHeightPx: Int,
        densityDpi: Int = 0,
        fontScale: Float = 1f,
        rtl: Boolean = false
    ): Bitmap? {
        if (output.text.isBlank()) return null
        val safe = config.safe()
        val height = iconHeightPx.coerceIn(1, 512)
        val density = if (densityDpi > 0) densityDpi / 160f else height / 20f
        val safeFontScale = fontScale.takeIf(Float::isFinite)?.coerceIn(0.5f, 3f) ?: 1f
        val textSizePx = (safe.textSizeSp * density * safeFontScale).coerceIn(1f, 256f)
        val padStart = (safe.paddingStartSp * density).coerceIn(0f, 512f)
        val padEnd = (safe.paddingEndSp * density).coerceIn(0f, 512f)
        val key = listOf(
            output.text,
            output.isSingle,
            safe.fontMode,
            if (output.isSingle) safe.singleWeight else safe.weight,
            safe.condensedWidthPercent,
            textSizePx,
            height,
            padStart,
            padEnd,
            safe.verticalOffsetSp * density,
            rtl
        ).joinToString("|")
        synchronized(cache) { cache.get(key)?.let { return it } }

        val weight = if (output.isSingle) safe.singleWeight else safe.weight
        // Xiaomi's renderer leaves ordinary/default-font short labels such as "5G" at their
        // natural width. The condensed-width preference belongs to the optional custom font
        // path and only applies once the label needs that path; applying 80% unconditionally
        // made the standalone 5G holder visibly too narrow.
        val useCondensed = safe.fontMode != 0 && output.text.length > 2
        val family = if (safe.fontMode == 2 && useCondensed) {
            "sans-serif-condensed"
        } else {
            "sans-serif"
        }
        val textScale = if (safe.fontMode == 2) 1f else {
            if (useCondensed) safe.condensedWidthPercent / 100f else 1f
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.create(Typeface.create(family, Typeface.NORMAL), weight, false)
            textScaleX = textScale
            textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        val specialSuffix = if (useCondensed && output.text.length > 2 &&
            output.text in setOf("4G+", "5G+", "5GA")
        ) output.text.drop(2) else null
        val plusPaint = TextPaint(paint).apply { textSize = textSizePx * 0.7f }
        val hasDoublePlus = specialSuffix == null && output.text.endsWith("++")
        val baseText = when {
            specialSuffix != null -> output.text.take(2)
            hasDoublePlus -> output.text.dropLast(2)
            else -> output.text
        }
        val baseWidth = paint.measureText(baseText)
        val suffixText = specialSuffix ?: if (hasDoublePlus) "++" else ""
        val plusWidth = if (suffixText.isNotEmpty()) plusPaint.measureText(suffixText) else 0f
        val contentWidth = ceil(baseWidth + plusWidth).toInt().coerceAtLeast(1)
        val width = ceil((padStart + contentWidth + padEnd).toDouble())
            .toInt()
            .coerceIn(1, 2048)
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val metrics = paint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f +
            safe.verticalOffsetSp * density
        val baseX = if (rtl) width - padEnd else padStart
        canvas.drawText(baseText, baseX, baseline, paint)
        if (suffixText.isNotEmpty()) {
            val plusX = if (rtl) baseX - baseWidth else baseX + baseWidth
            canvas.drawText(suffixText, plusX, baseline, plusPaint)
        }
        synchronized(cache) { cache.put(key, bitmap) }
        return bitmap
    }

    fun clearCache() {
        synchronized(cache) { cache.evictAll() }
    }
}
