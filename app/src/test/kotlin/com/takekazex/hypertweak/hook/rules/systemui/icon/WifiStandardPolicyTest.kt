package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiStandardPolicyTest {
    @Test
    fun oneMapValueRepeatsAcrossStandards() {
        assertEquals(listOf(6, 6, 6, 6, 6), WifiStandardPolicy.parseMap("6"))
    }

    @Test
    fun malformedMapFallsBackToVerifiedDefaults() {
        assertEquals(WifiStandardPolicy.DEFAULT_MAP, WifiStandardPolicy.parseMap("4,5,broken"))
    }

    @Test
    fun customModeCanHideOneStandard() {
        val map = WifiStandardPolicy.parseMap("4,5,-1,7,8")
        assertEquals(0, WifiStandardPolicy.resolve(3, 6, map = map))
        assertEquals(8, WifiStandardPolicy.resolve(3, 8, map = map))
    }

    @Test
    fun rawModePreservesEightInsteadOfHostCollapsingIt() {
        assertEquals(8, WifiStandardPolicy.resolve(2, 8, original = 7))
    }
}
