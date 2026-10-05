package com.takekazex.hypertweak.hook.rules.personalassistant

import org.junit.Assert.*
import org.junit.Test

class AndroidWidgetGeometryTest {
    @Test fun `reported 4 by 2 providers remain 4 by 2 at different density and cell sizes`() {
        assertEquals(AndroidWidgetGeometry.Span(4, 2), AndroidWidgetGeometry.span(4, 2, 1000, 420, 280))
        assertEquals(AndroidWidgetGeometry.Span(4, 2), AndroidWidgetGeometry.span(4, 2, 750, 315, 210))
    }
    @Test fun `old providers without target cells use native pixel geometry with ceiling`() {
        assertEquals(AndroidWidgetGeometry.Span(4, 2), AndroidWidgetGeometry.span(0, 0, 1000, 420, 280))
        assertEquals(AndroidWidgetGeometry.Span(2, 2), AndroidWidgetGeometry.span(0, 0, 281, 281, 280))
        assertEquals(AndroidWidgetGeometry.Span(1, 1), AndroidWidgetGeometry.span(0, 0, 0, 0, 280))
    }
    @Test fun `ordinary providers are not filtered by MIUI aspect or row whitelists`() {
        for (span in listOf(1 to 2, 2 to 3, 4 to 1, 2 to 1, 1 to 1, 3 to 2, 4 to 5)) {
            assertEquals(AndroidWidgetGeometry.Span(span.first, span.second),
                AndroidWidgetGeometry.span(span.first, span.second, 100, 100, 280))
        }
    }
    @Test fun `invalid or overflowing geometry never shrinks silently or invents a default cell`() {
        assertNull(AndroidWidgetGeometry.span(4, 2, 1000, 420, 0))
        assertNull(AndroidWidgetGeometry.span(0, 0, Int.MAX_VALUE, 420, 1))
        assertNull(AndroidWidgetGeometry.span(5, 2, 1000, 420, 280))
        assertNull(AndroidWidgetGeometry.span(0, 0, -1, 420, 280))

    }
}
