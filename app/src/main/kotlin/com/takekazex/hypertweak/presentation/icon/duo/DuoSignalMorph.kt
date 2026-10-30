package com.takekazex.hypertweak.presentation.icon.duo

import android.util.Xml
import androidx.core.graphics.PathParser
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderer
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class DuoMorphDot(val x: Float, val y: Float, val radius: Float, val alpha: Float)

/** An exact, validated four-bar SVG endpoint, paired with circles of the same path topology. */
class DuoSignalBars private constructor(val left: Float, val top: Float, val width: Float, val height: Float,
                                       val bars: List<Array<PathParser.PathDataNode>>) {
    companion object {
        private const val TYPES = "MCCCHCCCVCCCHCCCVZ"
        // Three 30-degree cubics per quadrant preserve a circular starting silhouette.
        val circle: List<FloatArray> = buildList {
            add(floatArrayOf(-1f, 0f))
            val step = Math.PI / 6.0
            val k = 4.0 / 3.0 * tan(step / 4.0)
            repeat(4) { quadrant ->
                repeat(3) { segment ->
                    val a = Math.PI + quadrant * Math.PI / 2.0 + segment * step
                    val b = a + step
                    add(floatArrayOf((cos(a) - k * sin(a)).toFloat(), (sin(a) + k * cos(a)).toFloat(),
                        (cos(b) + k * sin(b)).toFloat(), (sin(b) - k * cos(b)).toFloat(),
                        cos(b).toFloat(), sin(b).toFloat()))
                }
                add(floatArrayOf(0f)) // Collapsed H/V segments become the straight bar edges.
            }
            add(floatArrayOf())
        }

        fun fromDocument(document: IconSvgRenderer.Document): DuoSignalBars {
            val parser = Xml.newPullParser().apply { setInput(StringReader(document.source)) }
            val found = HashMap<Int, Array<PathParser.PathDataNode>>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "path") {
                    val id = parser.getAttributeValue(null, "id")
                    val index = id?.removePrefix("signal_")?.toIntOrNull()
                    if (index in 1..4) {
                        val nodes = PathParser.createNodesFromPathData(parser.getAttributeValue(null, "d"))
                        require(nodes != null && nodes.size == TYPES.length) { "Signal bar topology changed: $id" }
                        nodes.forEachIndexed { i, node ->
                            require(node.type == TYPES[i] && node.params.size == circle[i].size && node.params.all { it.isFinite() }) {
                                "Signal bar commands changed: $id"
                            }
                        }
                        require(found.put(index!!, nodes) == null) { "Duplicate signal bar: $id" }
                    }
                }
                event = parser.next()
            }
            require(found.size == 4) { "Four independent signal paths required" }
            val box = document.metadata.viewBox
            require(box.width() > 0f && box.height() > 0f)
            return DuoSignalBars(box.left, box.top, box.width(), box.height(), (1..4).map { found.getValue(it) })
        }
    }
}

data class DuoDotMorph(val dots: List<DuoMorphDot>, val target: DuoSignalBars, val level: Int)
