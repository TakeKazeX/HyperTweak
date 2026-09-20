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
}
