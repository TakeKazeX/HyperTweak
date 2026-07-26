package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureBarGestureBridgeTest {
    @Test
    fun recognizesBeforeHyperOsShortLongPressTimeout() {
        assertEquals(225L, GestureBarLongPressTiming.recognitionTimeout(300L))
    }

    @Test
    fun preservesLeadForDefaultAndroidTimeout() {
        assertEquals(425L, GestureBarLongPressTiming.recognitionTimeout(500L))
    }

    @Test
    fun neverExtendsAnAlreadyShortSystemTimeout() {
        assertEquals(150L, GestureBarLongPressTiming.recognitionTimeout(150L))
    }
}
