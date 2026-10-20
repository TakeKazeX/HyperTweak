package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.*
import org.junit.Test
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarState.Action.*

/**
 * Timing contract of the gesture bar during the recent-tasks cycle.
 *
 * These cases encode the device-reported regressions, in the order they were found:
 * 1. the bar vanished the instant a swipe started (fade was anchored on the swipe);
 * 2. it then vanished without a fade at all (the draw gate cancelled the animation);
 * 3. it flashed at the bottom of the screen and reappeared in Recents afterwards (the fade and
 *    the reveal were played at display level, where the platform owns its own copy).
 *
 * The resulting contract: fade inside the application while the bar still belongs to it, stop
 * drawing the instant the platform takes the token back to display level, and never restore alpha
 * for a display that is still suppressed.
 */
class GestureBarStateTest {
    private val t0 = 1_000_000L

    @Test fun `the bar is drawn for the whole swipe-up transition`() {
        val state = GestureBarState()
        // The attachment arrives when the gesture starts.
        assertEquals(NONE, state.onAttachment(0, true, t0))
        assertFalse(state.isSuppressed(0))
        assertTrue(state.isTracked(0))
        // Still drawn while the application travels to Recents.
        assertEquals(NONE, state.onOverview(0, true, t0 + 200))
        assertFalse(state.isSuppressed(0))
    }

    @Test fun `the fade plays inside the application, before the token returns to the display`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        // The delayed in-app fade is the only place the bar may animate out.
        assertEquals(FADE_OUT, state.onDeadline(0, state.deadlineFor(0), true))
        // Still suppressed: the session is not over, only the animation is.
        assertTrue(state.isSuppressed(0))
    }

    @Test fun `the transition end stops drawing at once, never fades at display level`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        // Regression: fading here is what flashed at the bottom of the screen, because the
        // platform has just restored its own copy at display level and fades that in.
        assertEquals(HIDE_NOW, state.onAttachment(0, false, t0 + 1_000))
        assertTrue(state.isSuppressed(0))
    }

    @Test fun `a cancelled swipe never hides anything`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        assertEquals(NONE, state.onOverview(0, false, t0 + 300))
        assertEquals(NONE, state.onDeadline(0, state.deadlineFor(0), false))
        assertFalse(state.isSuppressed(0))
        assertFalse(state.isTracked(0))
    }

    @Test fun `a stale platform answer cannot discard a launcher-reported entry`() {
        // Regression: the leaving deadline asked the platform, which on HYOS answers "not in
        // Recents" even while the Dart launcher is showing it. The session was dropped and the
        // bar stayed drawn at the bottom of Recents.
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onOverview(0, true, t0 + 100)
        // No transition end arrives; the deadline fires with the platform's stale answer.
        assertEquals(FADE_OUT, state.onDeadline(0, state.deadlineFor(0), false))
        assertTrue(state.isSuppressed(0))
        // And it stays hidden for as long as the launcher says Recents is on screen.
        repeat(2) {
            assertEquals(NONE, state.onDeadline(0, state.deadlineFor(0), false))
            assertTrue(state.isSuppressed(0))
        }
    }

    @Test fun `the overview exit arms the return and the finished transition reveals`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onAttachment(0, false, t0 + 1_000)
        assertEquals(NONE, state.onOverview(0, false, t0 + 3_000))
        assertTrue(state.isSuppressed(0))
        assertEquals(SHOW, state.onTransitionFinished(0, false))
        assertFalse(state.isSuppressed(0))
        assertFalse(state.isTracked(0))
    }

    @Test fun `the finished leaving transition cannot reveal inside recents`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onOverview(0, true, t0 + 100)
        state.onAttachment(0, false, t0 + 1_000)
        // Recents is on screen: the leaving transition's own finish must not draw the bar.
        assertEquals(NONE, state.onTransitionFinished(0, true))
        assertTrue(state.isSuppressed(0))
    }

    @Test fun `a cancelled swipe restores the bar as soon as the transition ends`() {
        // Reported defect: swiping up without committing to Recents left the bar hidden until
        // the long stuck-report deadline, because a cancelled gesture reports no Overview at all.
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        // The leaving transition ends: the bar is hidden at once.
        assertEquals(HIDE_NOW, state.onAttachment(0, false, t0 + 1_000))
        assertTrue(state.isSuppressed(0))
        // The gesture was cancelled, so no Overview ever reported; the application is still on
        // screen and the finished transition restores the bar immediately.
        assertEquals(SHOW, state.onTransitionFinished(0, false))
        assertFalse(state.isSuppressed(0))
        assertFalse(state.isTracked(0))
    }

    @Test fun `a cancelled swipe also recovers through the first hidden probe`() {
        // Some cancelled gestures report no transition end either; the early probe covers them.
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onAttachment(0, false, t0 + 1_000)
        val firstProbe = state.deadlineFor(0)
        assertTrue("probe must be well before the stuck-report grace", firstProbe - t0 < 5_000)
        assertEquals(SHOW, state.onDeadline(0, firstProbe, false))
        assertFalse(state.isSuppressed(0))
    }

    @Test fun `nothing is restored while recents still owns the display`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onAttachment(0, false, t0 + 1_000)
        // Repeated deadlines with Recents visible must never report SHOW; that is the bar
        // reappearing at the bottom of the screen after a while.
        repeat(3) {
            assertEquals(NONE, state.onDeadline(0, state.deadlineFor(0), true))
            assertTrue(state.isSuppressed(0))
        }
    }

    @Test fun `the launcher report outranks a stale platform answer`() {
        // Regression: HYOS runs Recents in its Dart launcher, so ShellController.isOverviewVisible
        // kept answering "false" while the user was genuinely in Recents. Trusting that drew the
        // bar at the bottom of the screen.
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onOverview(0, true, t0 + 100)
        state.onAttachment(0, false, t0 + 1_000)
        assertTrue(state.isSuppressed(0))
        // The platform claims Recents is gone, but the launcher said it is on screen.
        repeat(3) {
            assertEquals(NONE, state.onDeadline(0, state.deadlineFor(0), false))
            assertTrue(state.isSuppressed(0))
        }
        // The same stale answer must not let a finished transition reveal it either.
        assertEquals(NONE, state.onTransitionFinished(0, false))
        assertTrue(state.isSuppressed(0))
        // Only the launcher's own exit ends the session.
        assertEquals(NONE, state.onOverview(0, false, t0 + 9_000))
        assertEquals(SHOW, state.onTransitionFinished(0, false))
        assertFalse(state.isSuppressed(0))
    }

    @Test fun `a return with no exit report still reveals on the transition`() {
        // With no Overview source at all, the finished transition plus the platform's own answer
        // is the evidence that the application is back.
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onAttachment(0, false, t0 + 1_000)
        assertEquals(SHOW, state.onTransitionFinished(0, false))
        assertFalse(state.isSuppressed(0))
    }

    @Test fun `the hidden deadline fails open when the platform says recents is gone`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onAttachment(0, false, t0 + 1_000)
        assertEquals(NONE, state.onDeadline(0, state.deadlineFor(0), true))
        assertEquals(SHOW, state.onDeadline(0, state.deadlineFor(0), false))
        assertFalse(state.isSuppressed(0))
    }

    @Test fun `an unreachable platform fails open only after bounded rounds`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onAttachment(0, false, t0 + 1_000)
        assertEquals(NONE, state.onDeadline(0, state.deadlineFor(0), null))
        assertTrue(state.isSuppressed(0))
        assertEquals(SHOW, state.onDeadline(0, state.deadlineFor(0), null))
        assertFalse(state.isSuppressed(0))
    }

    @Test fun `a new attachment during the return never restarts the visible phase`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onAttachment(0, false, t0 + 1_000)
        state.onOverview(0, false, t0 + 3_000)
        assertEquals(NONE, state.onAttachment(0, true, t0 + 3_100))
        assertTrue(state.isSuppressed(0))
        assertEquals(SHOW, state.onTransitionFinished(0, false))
    }

    @Test fun `displays are independent`() {
        val state = GestureBarState()
        state.onAttachment(0, true, t0)
        state.onAttachment(0, false, t0 + 1_000)
        state.onAttachment(2, true, t0)
        assertTrue(state.isSuppressed(0))
        assertFalse(state.isSuppressed(2))
        assertEquals(NONE, state.onDeadline(2, state.deadlineFor(2), false))
        assertFalse(state.isTracked(2))
        assertTrue(state.isSuppressed(0))
        state.clearDisplay(0)
        assertFalse(state.isSuppressed(0))
    }

    @Test fun `invalid display ids never create a session`() {
        val state = GestureBarState()
        assertEquals(NONE, state.onAttachment(-1, true, t0))
        assertEquals(NONE, state.onOverview(-1, true, t0))
        assertFalse(state.isSuppressed(-1))
        assertFalse(state.isSuppressed(null))
        assertFalse(state.isTracked(null))
    }

    @Test fun `replacement keeps the suppression and still restores on the transition`() {
        val old = GestureBarState()
        old.onAttachment(0, true, t0)
        old.onAttachment(0, false, t0 + 1_000)
        old.onOverview(0, false, t0 + 3_000)
        val next = GestureBarState().also { it.restore(old.save(), t0 + 3_100) }
        assertTrue(next.isSuppressed(0))
        assertEquals(SHOW, next.onTransitionFinished(0, false))
        assertFalse(next.isSuppressed(0))
    }

    @Test fun `a restored visible phase stays visible and keeps its own deadline`() {
        val old = GestureBarState().also { it.onAttachment(0, true, t0) }
        val restoredAt = t0 + 100
        val next = GestureBarState().also { it.restore(old.save(), restoredAt) }
        assertFalse(next.isSuppressed(0))
        assertTrue(next.deadlineFor(0) > restoredAt)
    }

    @Test fun `unknown or older saved layouts never invent a suppression`() {
        val state = GestureBarState()
        state.restore(null, t0)
        state.restore(intArrayOf(0, 0), t0)
        state.restore(intArrayOf(2, 0, 1, 0), t0)
        assertFalse(state.isSuppressed(0))
        assertFalse(state.isTracked(0))
    }
}
