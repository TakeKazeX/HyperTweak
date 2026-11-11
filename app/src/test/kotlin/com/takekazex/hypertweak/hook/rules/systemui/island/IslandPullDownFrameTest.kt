package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandPullDownFrameTest {
    @Test fun `the rest frame and the end of the Folme rebound restore native content`() {
        assertNull(IslandPullDownFrame.of(522, 522, 1f, blurSupported = true))
        assertNull(IslandPullDownFrame.of(0, 600, 0.5f, blurSupported = true))
    }

    @Test fun `content follows the grown outline up to the cap and blurs with drag progress`() {
        assertEquals(IslandPullDownFrame(1.5f, 20), IslandPullDownFrame.of(500, 750, 0.5f, blurSupported = true))
        assertEquals(IslandPullDownFrame.MAX_SCALE, IslandPullDownFrame.of(500, 2000, 0f, true)!!.scale)
        assertEquals(IslandPullDownFrame.MAX_BLUR, IslandPullDownFrame.of(500, 2000, 0f, true)!!.blurRadius)
    }

    @Test fun `without background material only the stretch applies`() {
        assertEquals(IslandPullDownFrame(1.2f, 0), IslandPullDownFrame.of(500, 600, 0.3f, blurSupported = false))
    }

    @Test fun `a rebound that has settled its height still clears the remaining blur`() {
        assertEquals(IslandPullDownFrame(1f, 4), IslandPullDownFrame.of(500, 500, 0.9f, blurSupported = true))
    }
}
