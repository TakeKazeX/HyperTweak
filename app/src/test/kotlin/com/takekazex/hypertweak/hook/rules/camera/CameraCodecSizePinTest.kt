package com.takekazex.hypertweak.hook.rules.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraCodecSizePinTest {

    @Test
    fun `matching size is not pinned`() {
        assertNull(CameraCodecSizePin.pinnedSize(1920, 1080, 1920, 1080))
    }

    @Test
    fun `width divergence is pinned back to initial size`() {
        assertEquals(1920 to 1080, CameraCodecSizePin.pinnedSize(3840, 1080, 1920, 1080))
    }

    @Test
    fun `height divergence is pinned back to initial size`() {
        assertEquals(1920 to 1080, CameraCodecSizePin.pinnedSize(1920, 2160, 1920, 1080))
    }

    @Test
    fun `fully different size is pinned back to initial size`() {
        assertEquals(1920 to 1080, CameraCodecSizePin.pinnedSize(640, 480, 1920, 1080))
    }

    @Test
    fun `zero-initial pin is deterministic like any other pair`() {
        // Defensive case: unreadable initial fields must be caught before this function runs
        // (the hooker returns early), so a 0 initial is never substituted from here.
        assertEquals(0 to 0, CameraCodecSizePin.pinnedSize(1920, 1080, 0, 0))
    }
}