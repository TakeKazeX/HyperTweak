package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.assertEquals
import org.junit.Test

class DuoLayoutTest {
    @Test fun rotationMovesClusterToNewEndInsteadOfRetainingPortraitCoordinates() {
        val portrait = DuoLayout.box(400, 30, 4, 0, 8, 0, 26, 4, false)
        val landscape = DuoLayout.box(800, 30, 4, 0, 8, 0, 26, 4, false)
        assertEquals(392, portrait.left + portrait.width)
        assertEquals(792, landscape.left + landscape.width)
        assertEquals(portrait.left + 400, landscape.left)
    }
    @Test fun gapIsReservedOutsideSquareGlyphAndRtlUsesOppositeEnd() {
        val box = DuoLayout.box(400, 30, 7, 0, 8, 0, 26, 4, true)
        assertEquals(7, box.left)
        assertEquals(4, box.width - box.height)
    }
    @Test fun narrowHeightHonorsPaddingWithoutStretchingGlyph() {
        val box = DuoLayout.box(100, 20, 0, 2, 0, 2, 26, 4, false)
        assertEquals(16, box.height)
        assertEquals(20, box.width)
        assertEquals(2, box.top)
    }
}
