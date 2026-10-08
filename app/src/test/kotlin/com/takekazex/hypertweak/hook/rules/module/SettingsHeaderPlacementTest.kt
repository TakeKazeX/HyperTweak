package com.takekazex.hypertweak.hook.rules.module

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHeaderPlacementTest {
    @Test fun insertsImmediatelyAfterMyDevice() {
        assertEquals(2, SettingsHeaderPlacement.after(anchorIndex = 1, listSize = 8))
    }

    @Test fun appendsWhenMyDeviceIsTheLastHeader() {
        assertEquals(1, SettingsHeaderPlacement.after(anchorIndex = 0, listSize = 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToGuessWhenTheNativeAnchorIsMissing() {
        SettingsHeaderPlacement.after(anchorIndex = -1, listSize = 8)
    }
}
