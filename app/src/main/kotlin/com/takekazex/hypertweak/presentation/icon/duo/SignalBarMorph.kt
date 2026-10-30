package com.takekazex.hypertweak.presentation.icon.duo

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.Xml
import androidx.core.graphics.PathParser
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderer
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Geometry alone; source/target paints are sampled from their actual bound views. */
class SignalBarMorph(val source: List<Contour>, val target: List<Contour>) {
    class Contour(val path: Path, val bounds: RectF, val points: FloatArray)

    companion object {
        const val SAMPLES = 128
        fun create(source: IconSvgRenderer.Document, target: IconSvgRenderer.Document,
                   row: Int, rows: Int, split: Float, weight: Float = 0f): SignalBarMorph {
            return SignalBarMorph(parse(source, row, rows, split, weight), parse(target, 0, 1, 1f, 0f))
        }
        private fun parse(document: IconSvgRenderer.Document, row: Int, rows: Int, split: Float,
                          weight: Float): List<Contour> {
            val xml = Xml.newPullParser().apply { setInput(StringReader(document.source)) }
            val box = document.metadata.viewBox
            val top = if (rows > 1 && row == 1) split else 0f
            val bottom = if (rows > 1 && row == 0) split else 1f
            val viewport = RectF(box.left, box.top + box.height() * top, box.right, box.top + box.height() * bottom)
            val transform = Matrix().apply { setRectToRect(viewport, RectF(0f, 0f, 1f, 1f), Matrix.ScaleToFit.FILL) }
            val result = HashMap<Int, Contour>()
            val prefix = if (rows > 1) "signal_${row + 1}_" else "signal_"
            var event = xml.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && xml.name == "path") {
                    val id = xml.getAttributeValue(null, "id").orEmpty()
                    val index = id.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
                    if (index in 1..4) {
                        var path = PathParser.createPathFromPathData(requireNotNull(xml.getAttributeValue(null, "d")))
                        if (rows > 1 && weight > 0f) {
                            val expanded = Path()
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.STROKE
                                strokeWidth = weight.coerceIn(0f, 1f) * .9f
                                strokeJoin = Paint.Join.ROUND
                            }.getFillPath(path, expanded)
                            path = Path().apply { op(path, expanded, Path.Op.UNION) }
                        }
                        path.transform(transform)
                        val bounds = RectF().also { path.computeBounds(it, true) }
                        require(!bounds.isEmpty)
                        result[index!!] = Contour(path, bounds, sample(path, bounds))
                    }
                }
                event = xml.next()
            }
            require(result.size == 4) { "Four SVG bar contours required" }
            return (1..4).map { result.getValue(it) }
        }
        private fun sample(path: Path, bounds: RectF): FloatArray {
            val measure = PathMeasure(path, true)
            require(measure.length > 0f)
            val position = FloatArray(2)
            var start = 0f; var nearest = Float.MAX_VALUE
            var area = 0f; var previousX = 0f; var previousY = 0f
            repeat(1024) { index ->
                val distance = measure.length * index / 1024f
                measure.getPosTan(distance, position, null)
                val dx = position[0] - bounds.centerX(); val dy = position[1] - bounds.top
                val score = dx * dx + dy * dy
                if (score < nearest) { nearest = score; start = distance }
                if (index > 0) area += previousX * position[1] - position[0] * previousY
                previousX = position[0]; previousY = position[1]
            }
            measure.getPosTan(0f, position, null)
            area += previousX * position[1] - position[0] * previousY
            val direction = if (area >= 0f) 1f else -1f
            return FloatArray(SAMPLES * 2).also { result ->
                repeat(SAMPLES) { i ->
                    val distance = ((start + direction * measure.length * i / SAMPLES) % measure.length + measure.length) % measure.length
                    measure.getPosTan(distance, position, null)
                    result[i * 2] = position[0]; result[i * 2 + 1] = position[1]
                }
            }
        }
    }
}
