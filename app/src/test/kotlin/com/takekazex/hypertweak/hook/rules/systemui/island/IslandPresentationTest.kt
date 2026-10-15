package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class IslandPresentationTest {
    private enum class State { SMALL, BIG, EXPANDED, HIDDEN, DELETED }
    private fun render(state: State, full: Boolean) = IslandPresentation.project(state, full, { it == State.SMALL }) { State.BIG }

    @Test fun `compact full compact transitions preserve native origin and target templates`() {
        assertEquals(State.SMALL to State.BIG, render(State.SMALL, false) to render(State.SMALL, true))
        assertEquals(State.BIG to State.SMALL, render(State.SMALL, true) to render(State.SMALL, false))
        assertEquals(State.BIG to State.BIG, render(State.SMALL, true) to render(State.SMALL, true))
    }

    @Test fun `expansion deletion and hiding keep original target semantics`() {
        for (target in listOf(State.EXPANDED, State.HIDDEN, State.DELETED)) {
            assertEquals(State.BIG to target, render(State.SMALL, true) to render(target, true))
            assertEquals(target to State.BIG, render(target, true) to render(State.SMALL, true))
        }
        val logical = State.SMALL
        render(logical, true)
        assertEquals(State.SMALL, logical)
    }

    @Test fun `expanded and departed tasks cannot reuse stale summary frames`() {
        val timer = Any()
        val media = Any()
        val oldFrames = setOf(timer, media)
        assertTrue(IslandPresentation.isSummary(media, oldFrames, listOf(timer, media), null))
        assertFalse(IslandPresentation.isSummary(media, oldFrames, listOf(timer, media), media))
        assertFalse(IslandPresentation.isSummary(media, oldFrames, listOf(timer), null))
        assertFalse(IslandPresentation.isSummary(media, setOf(timer), listOf(timer, media), null))
        assertTrue(IslandPresentation.isSummary(timer, oldFrames, listOf(timer), media))
    }

    @Test fun `active full summary content follows its own animated clipping centre`() {
        for (progress in 0..10) {
            val centre = 500f + 804f * progress / 10f
            val translation = IslandPresentation.contentTranslation(2608, centre - 250f, centre + 250f, 0f, 1f)
            assertEquals(centre, 1304f + translation, 0.001f)
        }
        assertEquals(804f, IslandPresentation.contentTranslation(2608, 1858f, 2358f, 0f, 1f), 0f)
        assertEquals(0f, IslandPresentation.contentTranslation(2608, 1858f, 2358f, 0f, 0f), 0f)
    }
}
