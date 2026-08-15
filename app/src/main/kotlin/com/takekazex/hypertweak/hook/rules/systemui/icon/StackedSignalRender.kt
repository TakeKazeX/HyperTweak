package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal SVG renderer for the stacked-signal icons, ported from Hyper Helper's `zh` parser and
 * `ys` render engine. The built-in signal SVGs only use `path`/`rect` elements with `id`
 * attributes (`signal_1..4` for single, `signal_1_1..signal_2_4` for stacked) plus an optional
 * `type_container` rect whose position anchors the type text, so the parser covers exactly that
 * subset. Everything renders to ALPHA_8 bitmaps that SystemUI tints through the normal status-bar
 * pipeline.
 */
object StackedSignalRender {

    private const val TAG = "IconTuner"

    class Element(val id: String?, val path: Path?, val rect: RectF?)
    class Doc(
        val elements: List<Element>,
        val width: Float,
        val height: Float,
        val anchor: PointF?
    )

    private val docCache = ConcurrentHashMap<String, Doc>()

    fun parse(xml: String): Doc? {
        docCache[xml.hashCode().toString()]?.let { return it }
        val doc = runCatching { doParse(xml) }.getOrNull() ?: return null
        docCache[xml.hashCode().toString()] = doc
        return doc
    }

    private fun doParse(xml: String): Doc {
        val elements = mutableListOf<Element>()
        var width = 0f
        var height = 0f
        var anchor: PointF? = null
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name
                val id = parser.getAttributeValue(null, "id")
                when (name) {
                    "svg" -> {
                        width = parser.getAttributeValue(null, "width")?.toFloatOrNull() ?: 0f
                        height = parser.getAttributeValue(null, "height")?.toFloatOrNull() ?: 0f
                        parser.getAttributeValue(null, "viewBox")?.let { vb ->
                            val parts = vb.trim().split(Regex("\\s+|,"))
                            if (parts.size == 4) {
                                // Scale the document into viewBox coordinates when they differ.
                                val vw = parts[2].toFloatOrNull() ?: width
                                val vh = parts[3].toFloatOrNull() ?: height
                                if (vw > 0f && vh > 0f && width <= 0f) {
                                    width = vw
                                    height = vh
                                }
                            }
                        }
                    }
                    "path" -> {
                        val d = parser.getAttributeValue(null, "d") ?: ""
                        elements.add(Element(id, parsePath(d), null))
                    }
                    "rect" -> {
                        val x = parser.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f
                        val y = parser.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f
                        val w = parser.getAttributeValue(null, "width")?.toFloatOrNull() ?: 0f
                        val h = parser.getAttributeValue(null, "height")?.toFloatOrNull() ?: 0f
                        val rect = RectF(x, y, x + w, y + h)
                        if (id == "type_container") {
                            anchor = PointF(rect.centerX(), rect.centerY())
                        }
                        elements.add(Element(id, null, rect))
                    }
                }
            }
            event = parser.next()
        }
        return Doc(elements, width, height, anchor)
    }

    /** Parses the path subset used by the signal SVGs: M/L/C/Z with absolute coordinates. */
    private fun parsePath(d: String): Path {
        val path = Path()
        val tokens = Regex("([MLCZmlcz])|(-?\\d+(?:\\.\\d+)?)").findAll(d)
        val nums = mutableListOf<Float>()
        var cmd = ' '
        var i = 0
        fun next(): Float = if (i < nums.size) nums[i++] else 0f
        for (m in tokens) {
            val c = m.groupValues[1]
            if (c.isNotEmpty()) {
                flush(cmd, nums, path)
                cmd = c[0]
                nums.clear()
                i = 0
            } else {
                m.groupValues[2].toFloatOrNull()?.let { nums.add(it) }
            }
        }
        flush(cmd, nums, path)
        return path
    }

    private fun flush(cmd: Char, nums: MutableList<Float>, path: Path) {
        var i = 0
        fun next(): Float = if (i < nums.size) nums[i++] else 0f
        when (cmd.uppercaseChar()) {
            'M' -> {
                path.moveTo(next(), next())
                while (i + 1 < nums.size) path.lineTo(next(), next())
            }
            'L' -> while (i + 1 < nums.size) path.lineTo(next(), next())
            'C' -> while (i + 5 < nums.size) {
                path.cubicTo(next(), next(), next(), next(), next(), next())
            }
            'Z' -> path.close()
        }
    }

    /**
     * Renders the bars of [doc] for [level] visible columns (0..4) into an ALPHA_8 bitmap of
     * height [outH]. For stacked documents the row is chosen by [row] (1 or 2).
     */
    fun renderBars(doc: Doc, row: Int, level: Int, outH: Int, alpha: Float): Bitmap? {
        if (outH <= 0 || doc.elements.isEmpty()) return null
        val scale = outH / doc.height
        val picture = Picture()
        val canvas = picture.beginRecording(doc.width.toInt(), doc.height.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = -1
            this.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        }
        canvas.scale(scale, scale)
        val visible = when {
            row > 0 -> (1..level).map { "signal_${row}_$it" }.toSet()
            else -> (1..level).map { "signal_$it" }.toSet()
        }
        for (e in doc.elements) {
            if (e.id == null || e.id in visible) {
                if (e.path != null) canvas.drawPath(e.path, paint)
                if (e.rect != null) canvas.drawRect(e.rect, paint)
            }
        }
        picture.endRecording()
        val bmp = Bitmap.createBitmap(
            maxOf(1, (doc.width * scale).toInt()),
            outH,
            Bitmap.Config.ALPHA_8
        )
        val outCanvas = Canvas(bmp)
        outCanvas.scale(bmp.width / picture.width.toFloat(), bmp.height / picture.height.toFloat())
        outCanvas.drawPicture(picture)
        return bmp
    }

    /** Renders the type text ("4G", "5G"...) into an ALPHA_8 bitmap of height [outH]. */
    fun renderTypeText(text: String, outH: Int, weight: Int, alpha: Float): Bitmap? {
        if (text.isEmpty() || outH <= 0) return null
        val typeface = typefaceFor(weight)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = -1
            textSize = outH * 0.55f
            this.typeface = typeface
            textAlign = Paint.Align.LEFT
            this.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        }
        val width = paint.measureText(text).toInt() + 2
        if (width <= 0) return null
        val fm = paint.fontMetrics
        val bmp = Bitmap.createBitmap(width, outH, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        val baseline = (outH - (fm.descent - fm.ascent)) / 2 - fm.ascent
        canvas.drawText(text, 1f, baseline, paint)
        return bmp
    }

    /** Combines the signal bars with the type text at the SVG anchor (or trailing edge). */
    fun compose(
        bars: Bitmap,
        type: Bitmap?,
        anchor: PointF?,
        rtl: Boolean,
        paddingStartPx: Int,
        paddingEndPx: Int,
        density: Float
    ): Bitmap {
        val anchorX = if (anchor != null) (anchor.x * density).toInt() else bars.width
        val typeOffset = if (type != null) {
            if (rtl) -type.width else 0
        } else 0
        val left = minOf(0, anchorX + typeOffset)
        val right = maxOf(bars.width, anchorX + typeOffset + (type?.width ?: 0))
        val width = right - left + paddingStartPx + paddingEndPx
        if (width <= 0) return bars
        val out = Bitmap.createBitmap(width, bars.height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(out)
        val dx = (paddingStartPx - left).toFloat()
        canvas.drawBitmap(bars, dx, 0f, null)
        if (type != null) {
            canvas.drawBitmap(
                type,
                dx + (anchorX + typeOffset).toFloat(),
                (bars.height - type.height) / 2f,
                null
            )
        }
        return out
    }

    private val weightTypefaces = ConcurrentHashMap<Int, Typeface?>()

    fun typefaceFor(weight: Int): Typeface? {
        return weightTypefaces.getOrPut(weight) {
            runCatching {
                Typeface.Builder("/system/fonts/MiSansVF.ttf")
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }.getOrNull()
        }
    }
}
