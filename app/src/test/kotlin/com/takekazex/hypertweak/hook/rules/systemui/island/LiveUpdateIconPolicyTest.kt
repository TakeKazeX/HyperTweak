package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class LiveUpdateIconPolicyTest {
    @Test fun `only native promoted monochrome slots qualify`() {
        assertTrue(LiveUpdateIconPolicy.eligible(true, LiveUpdateIconPolicy.PICTURE, 3, null))
        assertFalse(LiveUpdateIconPolicy.eligible(false, LiveUpdateIconPolicy.PICTURE, 3, null))
        assertFalse(LiveUpdateIconPolicy.eligible(true, "custom_logo", 3, null))
        assertFalse(LiveUpdateIconPolicy.eligible(true, LiveUpdateIconPolicy.PICTURE, 4, null))
        assertFalse(LiveUpdateIconPolicy.eligible(true, LiveUpdateIconPolicy.PICTURE, 3, "call"))
    }

    @Test fun `black vector and antialiased alpha masks need foreground but empty masks do not`() {
        assertTrue(LiveUpdateIconPolicy.darkMonochrome(intArrayOf(0, 0xff000000.toInt(), 0x80000000.toInt())))
        assertTrue(LiveUpdateIconPolicy.darkMonochrome(intArrayOf(0xff202020.toInt(), 0xff222020.toInt())))
        assertFalse(LiveUpdateIconPolicy.darkMonochrome(intArrayOf(0, 0x01000000)))
        assertFalse(LiveUpdateIconPolicy.darkMonochrome(intArrayOf()))
    }

    @Test fun `preserve colored logos white masks and black background artwork`() {
        for (pixel in intArrayOf(0xff005020.toInt(), 0xff201050.toInt(), 0xffffffff.toInt(), 0xffcccccc.toInt())) {
            assertFalse(LiveUpdateIconPolicy.darkMonochrome(intArrayOf(0, 0xff000000.toInt(), pixel)))
        }
    }
}
