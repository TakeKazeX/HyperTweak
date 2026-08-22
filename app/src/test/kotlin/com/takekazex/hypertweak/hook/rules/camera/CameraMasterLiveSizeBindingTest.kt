package com.takekazex.hypertweak.hook.rules.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraMasterLiveSizeBindingTest {

    @Test
    fun `ultra pixel binds 4x3`() {
        assertEquals(1728 to 1296, CameraMasterLiveSizeBinding.targetSize("0"))
    }

    @Test
    fun `movement types bind 16x9`() {
        assertEquals(2304 to 1296, CameraMasterLiveSizeBinding.targetSize("1"))
        assertEquals(2304 to 1296, CameraMasterLiveSizeBinding.targetSize("2"))
        assertEquals(2304 to 1296, CameraMasterLiveSizeBinding.targetSize("3"))
    }

    @Test
    fun `unknown or unreadable type falls back to 16x9`() {
        assertEquals(2304 to 1296, CameraMasterLiveSizeBinding.targetSize(null))
        assertEquals(2304 to 1296, CameraMasterLiveSizeBinding.targetSize(""))
        assertEquals(2304 to 1296, CameraMasterLiveSizeBinding.targetSize("9"))
    }

    @Test
    fun `boundSize returns null when the size already matches the target`() {
        assertNull(CameraMasterLiveSizeBinding.boundSize("0", 1728, 1296))
        assertNull(CameraMasterLiveSizeBinding.boundSize("2", 2304, 1296))
        assertNull(CameraMasterLiveSizeBinding.boundSize(null, 2304, 1296))
    }

    @Test
    fun `boundSize substitutes mismatched sizes per type`() {
        // 超清实况 must NOT keep the 16:9 pin (the 2026-08-28 green-screen regression).
        assertEquals(1728 to 1296, CameraMasterLiveSizeBinding.boundSize("0", 2304, 1296))
        // movement types keep the proven 16:9 pin
        assertEquals(2304 to 1296, CameraMasterLiveSizeBinding.boundSize("3", 2560, 1440))
        // swapped orientation is also substituted (same convention as the previous global pin)
        assertEquals(2304 to 1296, CameraMasterLiveSizeBinding.boundSize("2", 1296, 2304))
    }
}
