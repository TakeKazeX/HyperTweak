package com.takekazex.hypertweak.hook.rules.module

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHeaderPlacementTest {
    @Test fun insertsBeforeWifiAnchor() {
        assertEquals(3, SettingsHeaderPlacement.before(anchorIndex = 3, listSize = 8))
    }

    @Test fun usesStableFallbackForMissingAnchor() {
        assertEquals(2, SettingsHeaderPlacement.before(anchorIndex = -1, listSize = 8))
        assertEquals(1, SettingsHeaderPlacement.before(anchorIndex = -1, listSize = 1))
    }
}
