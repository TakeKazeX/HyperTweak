package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandPresentation
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandRow
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize

import org.junit.Assert.*
import org.junit.Test

class IslandPresentationTest {
    @Test fun `unchanged borrowed neighbours stay displayed but track updates still publish`() {
        val frame = FullIslandFrame(800, IslandContentSize(80, 200))
        assertTrue(IslandPresentation.keepBorrowedSummary(frame, frame.copy(), false, false))
        assertFalse(IslandPresentation.keepBorrowedSummary(frame, frame, true, false))
        assertFalse(IslandPresentation.keepBorrowedSummary(frame, frame, false, true))
        assertFalse(IslandPresentation.keepBorrowedSummary(frame, frame.copy(x = 900), false, false))
        assertFalse(IslandPresentation.keepBorrowedSummary(null, frame, false, false))
        assertFalse(IslandPresentation.keepBorrowedSummary(frame, null, false, false))
        val compact = frame.copy(full = false)
        assertFalse(IslandPresentation.keepBorrowedSummary(compact, compact, false, false))
    }

    @Test fun `expanding a neighbour only republishes survivors whose own frame changes`() {
        val content = IslandContentSize(90, 210)
        val before = FullIslandRow.calculate(2400, 1080, 100, 100,
            listOf(content, content, content), 20)!!
        val after = FullIslandRow.calculate(2400, 1080, 100, 100,
            listOf(content, content), 20)!!
        // Removing a seat moves the remaining row. Both survivors need final coordinates.
        for (i in after.indices) {
            assertTrue(IslandPresentation.needsPublication(before[i], after[i], true, false))
        }
        // A refill can preserve one survivor exactly while changing the other task/seat.
        assertFalse(IslandPresentation.needsPublication(before[0], before[0].copy(), true, false))
        assertTrue(IslandPresentation.needsPublication(before[0], before[0], false, true))
        assertTrue(IslandPresentation.needsPublication(before[0], before[0].copy(content = IslandContentSize(120, 180)), true, false))
        assertTrue(IslandPresentation.needsPublication(before[0], before[0].copy(full = false), true, false))
        assertTrue(IslandPresentation.needsPublication(null, after[0], true, false))
        assertTrue(IslandPresentation.needsPublication(before[0], null, true, false))
        assertFalse(IslandPresentation.needsPublication(null, null, false, false))
    }

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
