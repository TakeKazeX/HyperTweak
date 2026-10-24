package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** Independent bounds from the real bundled contours, including cubic derivative extrema. */
class SignalArtworkGeometryTest {
    private data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double)
    private val tokens = Regex("[A-Za-z]|[-+]?(?:[0-9]*[.][0-9]+|[0-9]+)(?:[eE][-+]?[0-9]+)?")

    private fun extrema(v: List<Double>): List<Double> {
        val a = -v[0] + 3*v[1] - 3*v[2] + v[3]
        val b = 2*(v[0] - 2*v[1] + v[2])
        val c = v[1] - v[0]
        val ts = mutableListOf(0.0, 1.0)
        if (abs(a) < 1e-12) {
            if (abs(b) > 1e-12) ts += -c/b
        } else if (b*b - 4*a*c >= 0) {
            val d = sqrt(b*b - 4*a*c)
            ts += (-b-d)/(2*a); ts += (-b+d)/(2*a)
        }
        return ts.filter { it in 0.0..1.0 }.map { t ->
            val u = 1-t
            u*u*u*v[0] + 3*u*u*t*v[1] + 3*u*t*t*v[2] + t*t*t*v[3]
        }
    }

    private fun bounds(path: String): Bounds {
        val t = tokens.findAll(path).map { it.value }.toList()
        var i = 0; var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0
        val xs = mutableListOf<Double>(); val ys = mutableListOf<Double>()
        fun n() = t[i++].toDouble()
        while (i < t.size) {
            when (val command = t[i++]) {
                "M" -> { x=n(); y=n(); sx=x; sy=y; xs+=x; ys+=y }
                "H" -> { x=n(); xs+=x; ys+=y }
                "V" -> { y=n(); xs+=x; ys+=y }
                "Z" -> { x=sx; y=sy; xs+=x; ys+=y }
                "C" -> {
                    val x1=n(); val y1=n(); val x2=n(); val y2=n(); val nx=n(); val ny=n()
                    xs+=extrema(listOf(x,x1,x2,nx)); ys+=extrema(listOf(y,y1,y2,ny)); x=nx; y=ny
                }
                else -> error("Unreviewed path command: $command")
            }
        }
        return Bounds(xs.min(),ys.min(),xs.max(),ys.max())
    }

    @Test fun realArtworkRemainsInsideCanvasAndEachHandoffCropAtEveryWeight() {
        for (style in SignalSvgStyle.entries) {
            val family = if (style == SignalSvgStyle.HYPEROS3) "HyperOS3" else "iOS27"
            val source = File("src/main/assets/svg/Signal-$family-Stacked.svg").readText()
            val original = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source.byteInputStream())
            for (weight in listOf(0f, .5f, 1f)) {
                val result = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(SignalWeight.apply(source, weight).byteInputStream())
                val box = result.documentElement.getAttribute("viewBox").split(" ").map { it.toDouble() }
                val split = style.stackedSplitFraction * box[3]
                val paths = result.getElementsByTagName("path")
                assertEquals(8, paths.length)
                val rows = mutableMapOf<Int, MutableList<Bounds>>()
                for (i in 0 until paths.length) {
                    val attrs = paths.item(i).attributes
                    val old = original.getElementsByTagName("path").item(i).attributes
                    assertEquals(old.getNamedItem("d").nodeValue, attrs.getNamedItem("d").nodeValue)
                    val width = attrs.getNamedItem("stroke-width")?.nodeValue?.toDouble() ?: 0.0
                    assertEquals(weight*.9, width, .00001)
                    val half = width/2
                    val b = bounds(attrs.getNamedItem("d").nodeValue)
                    val row = attrs.getNamedItem("id").nodeValue.split("_")[1].toInt()
                    assertTrue(b.left-half > 0 && b.right+half < box[2])
                    assertTrue(b.top-half > 0 && b.bottom+half < box[3])
                    if (row == 1) assertTrue("$family top row crosses crop", b.bottom+half < split)
                    else assertTrue("$family bottom row crosses crop", b.top-half > split)
                    rows.getOrPut(row) { mutableListOf() } += b
                }
                assertEquals(setOf(1,2), rows.keys)
                assertTrue(rows.getValue(2).minOf { it.top } - rows.getValue(1).maxOf { it.bottom } - weight*.9 > .45)
            }
            for (bad in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(source, SignalWeight.apply(source, bad))
        }
    }
}
