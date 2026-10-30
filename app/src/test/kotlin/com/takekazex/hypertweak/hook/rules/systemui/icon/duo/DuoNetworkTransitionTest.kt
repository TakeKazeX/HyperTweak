package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.*
import org.junit.Test

class DuoNetworkTransitionTest {
    private val cell = DuoRepresentation(false, "5G", false)
    private val wifi = DuoRepresentation(true, null, false)
    private val airplane = DuoRepresentation(false, null, true)

    @Test fun reversalPreservesTheVisibleMixture() {
        val transition = DuoNetworkTransition()
        assertFalse(transition.submit(cell, true))
        assertTrue(transition.submit(wifi, true))
        transition.advance(.35f)
        val before = transition.weights
        assertTrue(transition.submit(cell, true))
        transition.advance(0f)
        assertEquals(before, transition.weights)
        transition.advance(.5f)
        assertEquals(.175f, transition.weights[wifi]!!, .0001f)
        assertEquals(.825f, transition.weights[cell]!!, .0001f)
        transition.advance(1f)
        assertEquals(mapOf(cell to 1f), transition.weights)
    }

    @Test fun thirdRepresentationDoesNotDropAnAlreadyVisibleLayer() {
        val transition = DuoNetworkTransition()
        transition.submit(cell, true)
        transition.submit(wifi, true)
        transition.advance(.4f)
        transition.submit(airplane, true)
        transition.advance(.25f)
        assertEquals(.45f, transition.weights[cell]!!, .0001f)
        assertEquals(.30f, transition.weights[wifi]!!, .0001f)
        assertEquals(.25f, transition.weights[airplane]!!, .0001f)
        assertEquals(1f, transition.weights.values.sum(), .0001f)
    }

    @Test fun strengthUpdateDoesNotRestartTransition() {
        val transition = DuoNetworkTransition()
        transition.submit(cell, true)
        transition.submit(wifi, true)
        transition.advance(.6f)
        val before = transition.weights
        assertFalse(transition.submit(wifi, true))
        assertEquals(before, transition.weights)
        transition.submit(airplane, false)
        assertEquals(mapOf(airplane to 1f), transition.weights)
    }

    @Test fun activeSecondSimTravelsWithoutMergingTheOtherRow() {
        val a = com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow(11, 0, 4)
        val b = com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow(22, 1, 2)
        assertEquals(listOf(-1, 0), DuoTransportSubscriptions.match(listOf(a,b),listOf(b.copy(slotIndex=-1)),22))
        assertEquals(listOf(1, 0), DuoTransportSubscriptions.match(listOf(a,b),listOf(b,a),22))
        assertEquals(listOf(-1, 0), DuoTransportSubscriptions.match(listOf(a,b),listOf(b.copy(subId=-1,slotIndex=-1)),22))
    }

    @Test fun replacementSubscriptionDoesNotBorrowAnUnrelatedSignal() {
        val a = com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow(11, 0, 4)
        val b = a.copy(subId=22)
        assertEquals(listOf(-1), DuoTransportSubscriptions.match(listOf(a),listOf(b),22))
        assertEquals(listOf(0), DuoTransportSubscriptions.match(listOf(a.copy(subId=-1)),listOf(b),22))
        assertEquals(listOf(0), DuoTransportSubscriptions.match(listOf(a),listOf(a,b),11))
    }

    @Test fun dampedCurveSettlesWithoutOvershootAtAnyFrameRate() {
        val motion = DuoNetworkTransition()
        motion.submit(cell, true)
        motion.submit(wifi, true)
        var last = 0f
        for (time in 0L..900L step 7L) {
            motion.advanceTime(time)
            val p = motion.weights[wifi] ?: 0f
            assertTrue(p >= last - .00001f && p <= 1f)
            assertEquals(1f, motion.weights.values.sum(), .00001f)
            last = p
        }
        assertEquals(mapOf(wifi to 1f), motion.weights)
        val sparse = DuoNetworkTransition()
        sparse.submit(cell, true); sparse.submit(wifi, true)
        sparse.advanceTime(103)
        val dense = DuoNetworkTransition()
        dense.submit(cell, true); dense.submit(wifi, true)
        for (time in 0L..103L) dense.advanceTime(time)
        assertEquals(sparse.weights[wifi]!!, dense.weights[wifi]!!, .000001f)
    }

    @Test fun reversalRetainsVelocityInsteadOfRestartingFromRest() {
        val motion = DuoNetworkTransition()
        motion.submit(cell, true); motion.submit(wifi, true)
        motion.advanceTime(100)
        val previous = motion.weights[wifi]!!
        motion.advanceTime(101)
        val before = motion.weights[wifi]!!
        motion.submit(cell, true)
        motion.advanceTime(0)
        assertEquals(before, motion.weights[wifi]!!, .000001f)
        motion.advanceTime(1)
        val after = motion.weights[wifi]!!
        assertTrue(after > before) // Retains momentum briefly before smoothly turning around.
        assertEquals(before - previous, after - before, .0004f)
        motion.advanceTime(900)
        assertEquals(mapOf(cell to 1f), motion.weights)
    }

    @Test fun repeatedRetargetsKeepMixtureValidAndDisabledAnimationsSnap() {
        val motion = DuoNetworkTransition()
        motion.submit(cell, true)
        repeat(40) { index ->
            motion.submit(listOf(wifi,cell,airplane)[index % 3], true)
            for (time in 0L..50L step 5L) {
                motion.advanceTime(time)
                assertEquals(1f, motion.weights.values.sum(), .00001f)
                assertTrue(motion.weights.values.all { it in 0f..1f })
            }
        }
        motion.submit(airplane, false)
        assertEquals(mapOf(airplane to 1f), motion.weights)
        motion.submit(cell, true)
        motion.advanceTime(10)
        motion.finish()
        assertEquals(mapOf(cell to 1f), motion.weights)
    }
}
