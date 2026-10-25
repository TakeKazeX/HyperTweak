package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.SignalWeight

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class SignalWeightTest {
    private fun asset(name: String): String = File("src/main/assets/svg/$name.svg").readText()

    @Test fun defaultAndInvalidValuesKeepOriginalArtwork() {
        val source = asset("Signal-HyperOS3-Stacked")
        for (value in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(source, SignalWeight.apply(source, value))
        }
        assertEquals(1f, SignalWeight.normalize(2f))
    }

    @Test fun fractionalWeightsExpandAllEightContoursWithoutChangingPathsOrBadge() {
        val source = asset("Signal-HyperOS3-Stacked")
        fun parse(xml: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val original = parse(source)
        for (weight in listOf(.12345f, .5f, 1f)) {
            val result = parse(SignalWeight.apply(source, weight))
            val paths = result.getElementsByTagName("path")
            assertEquals(8, paths.length)
            for (i in 0 until paths.length) {
                val attributes = paths.item(i).attributes
                assertEquals(original.getElementsByTagName("path").item(i).attributes.getNamedItem("d").nodeValue,
                    attributes.getNamedItem("d").nodeValue)
                val width = attributes.getNamedItem("stroke-width").nodeValue.toFloat()
                assertEquals(weight * .9f, width, .00001f)
                assertTrue(1.35f - width >= .45f - .00001f)
            }
            assertEquals("0 0 20 20", result.documentElement.getAttribute("viewBox"))
            assertNull(result.getElementsByTagName("rect").item(0).attributes.getNamedItem("stroke"))
        }
    }

    @Test fun singleSignalAndWifiAreUnaffected() {
        val single = asset("Signal-HyperOS3-Single")
        assertEquals(single, SignalWeight.apply(single, 1f))
        val wifi = "<svg><path id=\"wifi_1\" d=\"M0 0Z\" fill=\"white\"/></svg>"
        assertEquals(wifi, SignalWeight.apply(wifi, 1f))
    }
}
