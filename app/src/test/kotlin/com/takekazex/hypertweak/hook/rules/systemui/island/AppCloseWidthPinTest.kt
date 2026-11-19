package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.hooks.AppCloseWidthPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCloseWidthPinTest {
    private var clock = 0L
    private val pin = AppCloseWidthPin { clock }
    private val view = Any()
    private val widths: (Any) -> List<Int> = { listOf(it as Int) }
    private val same: (Any, Any) -> Boolean = { a, b -> a == b }

    @Test fun `lyrics appearing between close position and close start keep the rect WM received`() {
        assertTrue(pin.pin(view, 258, 258, widths))
        assertNull(pin.held(view, 258, same))
        assertEquals(258, pin.held(view, 714, same))
        assertTrue(pin.release(view))
        assertNull(pin.held(view, 714, same))
    }

    @Test fun `lyrics hiding between close position and close start keep the wide rect`() {
        assertTrue(pin.pin(view, 714, 714, widths))
        assertEquals(714, pin.held(view, 258, same))
        assertTrue(pin.release(view))
    }

    @Test fun `no pin when the rect does not come from the stored geometry`() {
        assertFalse(pin.pin(view, 276, 258, widths))
        assertNull(pin.held(view, 714, same))
    }

    @Test fun `an unchanged close needs no host re-measure and an old pin expires`() {
        pin.pin(view, 258, 258, widths)
        assertFalse(pin.release(view))
        pin.pin(view, 258, 258, widths)
        clock += AppCloseWidthPin.TIMEOUT_MS + 1
        assertNull(pin.held(view, 714, same))
    }
}
