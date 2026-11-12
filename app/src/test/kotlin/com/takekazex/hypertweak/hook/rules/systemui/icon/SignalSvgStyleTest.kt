package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.SignalSvgStyle

import org.junit.Assert.*
import org.junit.Test

class SignalSvgStyleTest {
    @Test fun newNumberingNeverReinterpretsLegacyIds() {
        assertEquals(SignalSvgStyle.HYPEROS3, SignalSvgStyle.fromStored(0))
        assertEquals(SignalSvgStyle.IOS27, SignalSvgStyle.fromStored(1))
        listOf(-1, 2, 3, 99).forEach { assertEquals(SignalSvgStyle.HYPEROS3, SignalSvgStyle.fromStored(it)) }
    }


}
