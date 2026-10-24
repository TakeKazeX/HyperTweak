package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class SignalSvgStyleTest {
    @Test fun newNumberingNeverReinterpretsLegacyIds() {
        assertEquals(SignalSvgStyle.HYPEROS3, SignalSvgStyle.fromStored(0))
        assertEquals(SignalSvgStyle.IOS27, SignalSvgStyle.fromStored(1))
        listOf(-1, 2, 3, 99).forEach { assertEquals(SignalSvgStyle.HYPEROS3, SignalSvgStyle.fromStored(it)) }
    }

    @Test fun newPresenceWinsEvenWhenInvalidAndOnlyOldStackedThreeMigrates() {
        val values = listOf(null, -1, 0, 1, 2, 3, 99)
        for (current in values) for (legacy in values) {
            assertEquals(if (current == 1) SignalSvgStyle.IOS27 else SignalSvgStyle.HYPEROS3,
                SignalSvgStyle.resolve(true, current, legacy))
            assertEquals(if (legacy == 3) SignalSvgStyle.IOS27 else SignalSvgStyle.HYPEROS3,
                SignalSvgStyle.resolve(false, current, legacy))
        }
    }
}
