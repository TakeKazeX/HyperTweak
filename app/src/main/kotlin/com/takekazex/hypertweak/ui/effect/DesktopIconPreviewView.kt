package com.takekazex.hypertweak.ui.effect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.takekazex.hypertweak.presentation.launcher.DesktopIconPreviewGeometry

internal data class DesktopPreviewIcon(val bitmap: Bitmap, val label: String)

/** A drawn illustration: no wallpaper permission, host access or screenshots. */
internal class DesktopIconPreviewView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    }
    private var icons: List<DesktopPreviewIcon> = emptyList()
    private var columns = 4
    private var rows = 6
    private var progress = DesktopIconPreviewGeometry.DEFAULT_PROGRESS
    private var foreground = Color.WHITE

    fun update(samples: List<DesktopPreviewIcon>, columns: Int, rows: Int, progress: Int, foreground: Int) {
        this.icons = samples
        this.columns = columns
        this.rows = rows
        this.progress = progress
        this.foreground = foreground
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || icons.isEmpty()) return
        // A stable logical desktop, uniformly scaled to fit the card on narrow screens.
        val scale = width / 360f
        val logicalHeight = height / scale
        text.textSize = 11f * resources.configuration.fontScale
        text.color = foreground
        outline.color = foreground
        outline.alpha = 85
        val textHeight = text.fontMetrics.let { it.descent - it.ascent }
        val layout = DesktopIconPreviewGeometry.resolve(360f, logicalHeight, columns, rows, progress, textHeight, 4f)
        canvas.save()
        canvas.scale(scale, scale)
        layout.cells.forEachIndexed { index, cell ->
            val sample = icons[index % icons.size]
            val centerX = cell.left + cell.size / 2
            val centerY = cell.top + cell.size / 2
            val half = layout.baselineSize / 2
            canvas.drawRoundRect(RectF(centerX - half, centerY - half, centerX + half, centerY + half), 10f, 10f, outline)
            canvas.drawBitmap(sample.bitmap, null, RectF(cell.left, cell.top, cell.left + cell.size, cell.top + cell.size), paint)
            val label = TextUtils.ellipsize(sample.label, text, (layout.cellWidth - 8f).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
            canvas.save()
            val top = index / columns * logicalHeight / rows
            canvas.clipRect(index % columns * layout.cellWidth, top, (index % columns + 1) * layout.cellWidth, top + logicalHeight / rows)
            canvas.drawText(label, centerX, cell.labelTop - text.fontMetrics.ascent, text)
            canvas.restore()
        }
        canvas.restore()
    }
}
