package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class ThreeIslandInteractionTest {
    @Test fun `four tasks keep three summary seats while the fourth is expanded`() {
        assertTrue(ThreeIslandAdmission.reserve(true, true, true, true))
        assertTrue(ThreeIslandAdmission.reserve(true, true, true, false))
        assertTrue(ThreeIslandAdmission.reserve(true, true, false, false))
    }
    @Test fun `expanding one of three retains remaining small seat until card closes`() {
        assertTrue(ThreeIslandAdmission.reserve(true, false, true, true))
        assertFalse(ThreeIslandAdmission.reserve(true, false, false, true))
        assertFalse(ThreeIslandAdmission.reserve(false, true, true, true))
        assertFalse(ThreeIslandAdmission.reserve(true, false, true, false))
    }
    @Test fun `left media and right task keep physical seats through ownership exchange`() {
        val seats = SmallIslandSeats()
        seats.assign("timer", "media")
        assertEquals(false, seats.left("timer"))
        assertEquals(true, seats.left("media"))
        seats.assign("media", "timer")
        assertTrue(seats.primaryOnLeft)
        assertFalse(seats.extraOnLeft)
        assertEquals(true, seats.left("media"))
        assertEquals(false, seats.left("timer"))
    }
    @Test fun `opening left media preserves right backup while a fourth takes vacated seat`() {
        val seats = SmallIslandSeats()
        seats.assign("timer", "media")
        seats.assign("timer", "delivery")
        assertEquals(false, seats.left("timer"))
        assertEquals(true, seats.left("delivery"))
        seats.assign("timer", "media") // Card collapse: the same task key returns.
        assertEquals(false, seats.left("timer"))
        assertEquals(true, seats.left("media"))
    }
    @Test fun `opening right task retains left backup even without a third summary task`() {
        val seats = SmallIslandSeats()
        seats.assign("timer", "media")
        seats.assign("media", null)
        assertTrue(seats.primaryOnLeft)
        seats.assign("media", "delivery")
        assertEquals(true, seats.left("media"))
        assertEquals(false, seats.left("delivery"))
    }
    @Test fun `replacement views retain seats by key and collisions resolve without overlap`() {
        val seats = SmallIslandSeats()
        seats.assign("timer", "media")
        seats.assign(String("timer".toCharArray()), String("media".toCharArray()))
        assertFalse(seats.primaryOnLeft)
        assertTrue(seats.extraOnLeft)
        seats.assign("timer", "next")
        seats.assign("next", "media")
        assertTrue(seats.primaryOnLeft)
        assertFalse(seats.extraOnLeft)
        assertNotEquals(seats.left("next"), seats.left("media"))
    }
    @Test fun `expanded drag and reset do not animate background summary tasks`() {
        val scope = IslandGestureScope()
        scope.begin("media", expanded = true)
        assertTrue(scope.allows("media"))
        assertFalse(scope.allows("timer"))
        assertFalse(scope.allows("delivery"))
        assertFalse(scope.allows(null))
        assertEquals("media", scope.clickKey)
        scope.begin("timer") // The next DOWN ends the previous expanded gesture scope.
        assertNull(scope.expandedKey)
        assertTrue(scope.allows("delivery"))
    }
    @Test fun `click stays bound to DOWN task despite replacement or disappearance`() {
        val scope = IslandGestureScope()
        scope.begin("media")
        val occupants = listOf("timer", "delivery", "fourth")
        assertNull(occupants.firstOrNull { it == scope.clickKey })
        assertEquals("media", scope.clickKey)
        scope.begin(null)
        assertNull(scope.clickKey)
    }
    @Test fun `cancelled expanded gesture cannot collapse a card and next DOWN resets cancellation`() {
        val scope = IslandGestureScope()
        scope.begin("media", expanded = true)
        scope.cancel()
        assertFalse(scope.mayCollapse("media"))
        scope.begin("media", expanded = true)
        assertTrue(scope.mayCollapse("media"))
    }
    @Test fun `replacement expanded card cannot inherit old gesture release`() {
        val scope = IslandGestureScope()
        scope.begin("media", expanded = true)
        assertFalse(scope.mayCollapse("timer"))
        assertFalse(scope.mayCollapse(null))
        assertTrue(scope.mayCollapse("media"))
    }
}
