package com.takekazex.hypertweak.hook.rules.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsScopeTest {
    @Test
    fun onlyAirPodsAndAirPodsMaxAreInScope() {
        assertTrue(AirPodsScope.isAirPodsType(5))
        assertTrue(AirPodsScope.isAirPodsType(6))
        assertFalse(AirPodsScope.isAirPodsType(0))
        assertFalse(AirPodsScope.isAirPodsType(1))
        assertFalse(AirPodsScope.isAirPodsType(null))

        assertTrue(AirPodsScope.isAirPodsInfo(HeadsetDeviceInfo(5)))
        assertTrue(AirPodsScope.isAirPodsInfo(HeadsetDeviceInfo(6)))
        assertFalse(AirPodsScope.isAirPodsInfo(HeadsetDeviceInfo(1)))
        assertTrue(AirPodsScope.isAirPodsScope(HeadsetDeviceInfo(5)))
        assertFalse(AirPodsScope.isAirPodsScope(HeadsetDeviceInfo(1)))
        assertFalse(AirPodsScope.isAirPodsInfo(OppoPodsInfo()))
        assertFalse(AirPodsScope.isAirPodsInfo(null))
        assertNull(AirPodsScope.headsetType(null))
    }

    @Test
    fun spatialAudioRequiresBothTheSwitchAndAirPodsScope() {
        val original = "01"
        val types = listOf(5, 6, 1, null)
        for (type in types) {
            assertEquals(
                if (type == 5 || type == 6) "00" else original,
                AirPodsScope.spatialValue(original, disableSpatialAudio = true, type = type)
            )
            assertEquals(
                original,
                AirPodsScope.spatialValue(original, disableSpatialAudio = false, type = type)
            )
        }
    }

    @Test
    fun adaptiveAncRequiresItsOwnSwitchAndAirPodsScope() {
        val types = listOf(5, 6, 1, null)
        for (type in types) {
            val inScope = type == 5 || type == 6
            assertEquals(
                if (inScope) "04" else "01",
                AirPodsScope.ancValue("01", forceAdaptiveAnc = true, type = type)
            )
            assertEquals(
                "01",
                AirPodsScope.ancValue("01", forceAdaptiveAnc = false, type = type)
            )
            assertEquals(
                if (inScope) 2 else 4,
                AirPodsScope.ancMode(4, forceAdaptiveAnc = true, type = type)
            )
            assertEquals(
                "关闭",
                AirPodsScope.ancTitle("关闭", forceAdaptiveAnc = false, type = type)
            )
            assertEquals(
                if (inScope) "自适应" else "关闭",
                AirPodsScope.ancTitle("关闭", forceAdaptiveAnc = true, type = type)
            )
        }
    }

    @Test
    fun theTwoFeatureSwitchesCoverAllFourCombinationsIndependently() {
        for (disableSpatialAudio in listOf(false, true)) {
            for (forceAdaptiveAnc in listOf(false, true)) {
                assertEquals(
                    if (disableSpatialAudio) "00" else "01",
                    AirPodsScope.spatialValue("01", disableSpatialAudio, 5)
                )
                assertEquals(
                    if (forceAdaptiveAnc) "04" else "01",
                    AirPodsScope.ancValue("01", forceAdaptiveAnc, 5)
                )
            }
        }
    }

    @Test
    fun unrelatedValuesAndUnknownDevicesRemainUnchanged() {
        assertEquals("00", AirPodsScope.spatialValue("00", true, 5))
        assertEquals("02", AirPodsScope.spatialValue("02", true, 5))
        assertEquals("04", AirPodsScope.ancValue("01", true, 5))
        assertEquals("00", AirPodsScope.ancValue("00", true, 5))
        assertEquals(3, AirPodsScope.ancMode(3, true, 5))
        assertEquals("降噪", AirPodsScope.ancTitle("降噪", true, 5))

        assertEquals(null, AirPodsScope.spatialValue(null, true, 5))
        assertEquals(null, AirPodsScope.ancValue(null, true, 5))
        assertEquals(4, AirPodsScope.ancMode(4, true, null))
        assertEquals("关闭", AirPodsScope.ancTitle("关闭", true, 1))
    }

    private class HeadsetDeviceInfo(
        @JvmField val type: Int,
        @JvmField val mac: String = "AA:BB:CC:DD:EE:FF"
    )

    private class OppoPodsInfo
}
