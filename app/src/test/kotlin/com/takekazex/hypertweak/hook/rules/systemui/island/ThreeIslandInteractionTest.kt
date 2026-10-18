package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandGestureScope
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SmallIslandSeats
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummaryIslandScheduler

import org.junit.Assert.*
import org.junit.Test

class ThreeIslandInteractionTest {
    @Test fun `music returning through left carousel inherits left seat over right history`() {
        val seats = SmallIslandSeats()
        val scheduler = SummaryIslandScheduler()
        seats.assign("media", "stopwatch") // Music previously occupied the right side.
        seats.assign("timer", "stopwatch")
        seats.assign("stopwatch", "timer") // DOWN claims the left native handler.
        val before = listOf("hotspot", "stopwatch", "timer")
        val change = scheduler.plan(before, listOf("media"), true, "stopwatch")!!
        seats.replace(change.displaced, change.task)
        seats.assign(change.visible[1], change.visible[2])
        assertEquals(listOf("hotspot", "media", "timer"), change.visible)
        assertTrue(seats.primaryOnLeft)
        assertEquals(true, seats.left("media"))
        assertEquals(false, seats.left("timer"))
        seats.assign("timer", "media") // Further native ownership exchange preserves it.
        assertEquals(true, seats.left("media"))
    }

    @Test fun `hide replacement inherits right seat without moving left neighbour`() {
        val seats = SmallIslandSeats()
        seats.assign("timer", "media")
        seats.assign("timer", "stopwatch")
        val change = SummaryIslandScheduler().plan(
            listOf("hotspot", "timer", "stopwatch"), listOf("media"), false, "timer")!!
        seats.replace(change.task, change.visible[1])
        seats.assign(change.visible[1], change.visible[2])
        assertEquals(false, seats.left("media"))
        assertEquals(true, seats.left("stopwatch"))
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
        assertFalse(scope.allows("delivery"))
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
