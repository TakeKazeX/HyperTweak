package com.takekazex.hypertweak.hook.rules.systemui.gesturebar

/**
 * Single source of truth for the gesture bar (小白条) during the recent-tasks cycle.
 *
 * The bar must stay on screen while the swipe-up transition plays - that is the stock behaviour,
 * and it is what makes the bar travel with the application when [Preferences] attach mode is on.
 * It may only disappear once that transition has placed Recents on screen.
 *
 * An earlier revision faded it out on the attachment event, which the platform sends at the very
 * beginning of the gesture, so the bar vanished the instant a swipe started. Device logs show the
 * attachment pair brackets the whole leaving transition: the start event arrives at the swipe, and
 * the restoring event about 0.9-1.3 s later when the token goes back to display level. The fade is
 * therefore anchored on that second event, with a deadline as the fallback when no transition runs.
 *
 * The session is a small phase machine per display:
 *
 * - IDLE: the bar is drawn normally.
 * - LEAVING: a swipe or an Overview entry started; the bar is still drawn and travels with the
 *   application. Nothing is hidden yet.
 * - HIDDEN: the leaving transition finished; the bar is suppressed for as long as Recents owns the
 *   bottom of the screen.
 * - RETURNING: the Overview exit was reported and the return transition is playing; still
 *   suppressed so the bar cannot appear at the display bottom ahead of the returning application.
 *
 * Every phase has a bounded deadline, so a lost platform event can neither leave the bar on screen
 * over Recents nor keep it hidden after the user is back in an application.
 */
internal class GestureBarState {
    enum class Action {
        NONE,
        /** Fade the bar out while it still belongs to the leaving application. */
        FADE_OUT,
        /** Stop drawing at once; the platform owns the display-level copy now. */
        HIDE_NOW,
        SHOW
    }

    private enum class Phase { IDLE, LEAVING, HIDDEN, RETURNING }

    private class Display(val id: Int) {
        var phase = Phase.IDLE
        /** Overview is currently on screen (last launcher report wins). */
        var overviewVisible = false
        /** Whether the authenticated launcher ever reported for this session. */
        var overviewReported = false
        /** Whether this session ever saw a real Overview report. */
        var sawOverview = false
        /** Uptime millis after which the current phase fails safe. */
        var deadlineAt = 0L
        /** Consecutive deadline rounds in which the platform could not answer. */
        var unknownQueries = 0
    }

    private val displays = HashMap<Int, Display>()

    private fun display(id: Int): Display? =
        if (id < 0) null else displays.getOrPut(id) { Display(id) }

    /**
     * The platform attached (attached = true) or restored (attached = false) the navigation token.
     *
     * Attaching happens when the gesture starts and the token is handed to the leaving application;
     * it must not hide anything. Restoring happens when the transition ends and the token returns to
     * display level, which is the moment Recents is in place - that is when the bar disappears.
     */
    @Synchronized fun onAttachment(display: Int, attached: Boolean, now: Long): Action {
        val state = display(display) ?: return Action.NONE
        if (attached) {
            if (state.phase == Phase.IDLE) {
                state.phase = Phase.LEAVING
                // Fade while the bar still belongs to the application, never at display level.
                state.deadlineAt = now + LEAVE_FADE_DELAY_MS
            }
            // HIDDEN and RETURNING already keep the bar off screen; a new attachment must not
            // restart the visible phase in the middle of a return.
            return Action.NONE
        }
        if (state.phase != Phase.LEAVING) return Action.NONE
        // The platform restored the token at display level and fades it IN there for the screen
        // that now owns the bottom. Drawing or fading at that moment is the reported flash at the
        // bottom of Recents, so suppression starts here and nothing is painted any more.
        state.phase = Phase.HIDDEN
        state.deadlineAt = now + HIDDEN_DEADLINE_MS
        state.unknownQueries = 0
        return Action.HIDE_NOW
    }

    /**
     * Launcher/Shell Overview visibility.
     *
     * Entering Recents starts a session when no transition reported one, but it never forces the
     * fade: the transition end owns that moment, so the bar keeps travelling with the application.
     * Leaving Recents arms the return; the finished return transition then reveals the bar.
     */
    @Synchronized fun onOverview(display: Int, visible: Boolean, now: Long): Action {
        val state = display(display) ?: return Action.NONE
        // The launcher's authenticated report is the only authority for this flag. The platform
        // query is a fallback used when no report ever arrived; overwriting this with it would let
        // a stale platform answer contradict the launcher while Recents is genuinely on screen.
        if (state.overviewReported && state.overviewVisible == visible) return Action.NONE
        state.overviewVisible = visible
        state.overviewReported = true
        if (visible) {
            state.sawOverview = true
            if (state.phase == Phase.IDLE) {
                state.phase = Phase.LEAVING
                state.deadlineAt = now + LEAVE_FADE_DELAY_MS
            } else if (state.phase == Phase.HIDDEN) {
                // Re-entered Recents (or the report arrived late): stay hidden, and push the
                // stuck-report guard out so the bar cannot surface while the user browses.
                state.deadlineAt = now + HIDDEN_GRACE_MS
                state.unknownQueries = 0
            }
            return Action.NONE
        }
        if (state.phase != Phase.HIDDEN) return Action.NONE
        state.phase = Phase.RETURNING
        state.deadlineAt = now + RETURN_DEADLINE_MS
        return Action.NONE
    }

    /**
     * The platform finished a transition for this display.
     *
     * The same callback covers both directions, so it has to be told apart by what the platform
     * reports right now: the host passes whether Recents is on screen.
     *
     * - RETURNING: the return transition completed; reveal.
     * - HIDDEN with Recents gone: a cancelled swipe. The bar was faded during the gesture but the
     *   application never left, so it must come straight back. Without this the session sat in
     *   HIDDEN until the long stuck-report deadline, which is the reported "the bar disappears and
     *   never returns after a cancelled swipe".
     * - HIDDEN with Recents still visible: the leaving transition's own finish; nothing to do.
     */
    @Synchronized fun onTransitionFinished(display: Int, platformOverview: Boolean?): Action {
        val state = displays[display] ?: return Action.NONE
        return when {
            state.phase == Phase.RETURNING -> reveal(state)
            // A cancelled swipe: the application never left, so nothing may stay hidden. This is
            // only trusted when the launcher has not spoken for this session - its authenticated
            // report is authoritative, and the platform query does not track HYOS's Dart Recents.
            state.phase == Phase.HIDDEN && !state.overviewReported &&
                platformOverview == false -> reveal(state)
            else -> Action.NONE
        }
    }

    /**
     * Bounded fallback for a phase whose own event never arrived. The host asks the platform first
     * and passes its answer, so this never guesses.
     *
     * - LEAVING: no transition end was reported. Outside Recents nothing was ever hidden, so the
     *   session is simply dropped; inside Recents the bar is hidden now, which is exactly the
     *   "wait a moment, then disappear" behaviour rather than an instant hide.
     * - HIDDEN: fail open, because a bar that never comes back is the defect being fixed.
     * - RETURNING: the return transition never reported; reveal anyway.
     */
    @Synchronized fun onDeadline(display: Int, now: Long, overviewVisible: Boolean?): Action {
        val state = displays[display] ?: return Action.NONE
        if (state.phase == Phase.IDLE) return Action.NONE
        // An early callback (clock skew, a coalesced post) is not evidence of anything.
        if (now < state.deadlineAt) return Action.NONE
        return when (state.phase) {
            Phase.LEAVING -> {
                // The launcher's authenticated report decides, not the platform query: HYOS runs
                // Recents in its Dart launcher, so ShellController.isOverviewVisible kept
                // answering "false" while Recents was genuinely on screen. Trusting it here threw
                // the session away and left the bar drawn at the bottom of Recents.
                val inRecents = if (state.overviewReported) state.overviewVisible else overviewVisible
                if (inRecents == false) {
                    // The gesture was cancelled or the entry never completed: nothing was hidden.
                    clearDisplayState(state)
                    Action.NONE
                } else {
                    // No transition end arrived. Fade inside the application now and keep the
                    // session suppressed, so the bar cannot reappear at the display bottom later.
                    state.phase = Phase.HIDDEN
                    state.deadlineAt = now + HIDDEN_DEADLINE_MS
                    Action.FADE_OUT
                }
            }
            Phase.RETURNING -> reveal(state)
            Phase.HIDDEN -> {
                // The launcher's authenticated report outranks the platform query: the query does
                // not follow HYOS's Dart Recents, so trusting it here drew the bar over Recents.
                if (state.overviewReported) {
                    if (state.overviewVisible) {
                        state.deadlineAt = now + HIDDEN_GRACE_MS
                        state.unknownQueries = 0
                        Action.NONE
                    } else {
                        // The launcher said Recents is gone; the return is simply outstanding.
                        reveal(state)
                    }
                } else {
                    when (overviewVisible) {
                        true -> {
                            state.deadlineAt = now + HIDDEN_GRACE_MS
                            state.unknownQueries = 0
                            Action.NONE
                        }
                        false -> reveal(state)
                        null -> {
                            if (++state.unknownQueries < UNKNOWN_QUERY_LIMIT) {
                                state.deadlineAt = now + HIDDEN_GRACE_MS
                                Action.NONE
                            } else {
                                // Nobody can answer; prefer a visible bar over a missing one.
                                reveal(state)
                            }
                        }
                    }
                }
            }
            Phase.IDLE -> Action.NONE
        }
    }

    /** Whether the bar must not be drawn for this display right now. */
    @Synchronized fun isSuppressed(display: Int?): Boolean =
        display != null && displays[display]?.let {
            it.phase == Phase.HIDDEN || it.phase == Phase.RETURNING
        } == true

    /**
     * Whether Recents currently owns the bottom of this display.
     *
     * The desktop switch asks this before hiding: HYOS implements Recents inside the launcher
     * activity, so the newest task keeps being Home while the recent-task list is on screen, and
     * hiding "the desktop" must not silently swallow that surface too.
     */
    @Synchronized fun isOverviewOnScreen(display: Int?): Boolean =
        display != null && displays[display]?.overviewVisible == true

    /** Only an unreported hidden session needs the platform's cancellation answer. */
    @Synchronized fun needsTransitionQuery(display: Int): Boolean =
        displays[display]?.let { it.phase == Phase.HIDDEN && !it.overviewReported } == true

    @Synchronized fun hasOverviewReport(display: Int): Boolean =
        displays[display]?.overviewReported == true

    /** Whether a session exists at all. */
    @Synchronized fun isTracked(display: Int?): Boolean =
        display != null && displays[display]?.let { it.phase != Phase.IDLE } == true

    /** The absolute deadline the host should schedule, or 0 when nothing is pending. */
    @Synchronized fun deadlineFor(display: Int): Long =
        displays[display]?.takeIf { it.phase != Phase.IDLE }?.deadlineAt ?: 0L

    /** Whether this session was started by a real Overview report, for host diagnostics. */
    @Synchronized fun sawOverview(display: Int): Boolean = displays[display]?.sawOverview == true

    @Synchronized fun clear() = displays.clear()

    @Synchronized fun clearDisplay(display: Int) {
        displays.remove(display)
    }

    /** Displays that currently hold a suppression, for host repaint after a settings change. */
    @Synchronized fun suppressedDisplays(): Set<Int> =
        displays.values.filter { it.phase == Phase.HIDDEN || it.phase == Phase.RETURNING }
            .map { it.id }.toSet()

    @Synchronized fun save(): IntArray {
        val rows = displays.values.sortedBy { it.id }.flatMap { state ->
            listOf(state.id, phaseFlag(state.phase) or
                (if (state.overviewVisible) OVERVIEW_VISIBLE else 0) or
                (if (state.overviewReported) OVERVIEW_REPORTED else 0) or
                (if (state.sawOverview) SAW_OVERVIEW else 0))
        }
        return intArrayOf(FORMAT_VERSION) + rows
    }

    /**
     * Restore across module replacement. Every phase gets a fresh deadline budget, so a replacement
     * during Recents cannot leave the bar hidden for the rest of the process.
     */
    @Synchronized fun restore(saved: Any?, now: Long) {
        val values = saved as? IntArray ?: return
        // Unknown or older layouts must not invent a suppression.
        if (values.isEmpty() || values[0] != FORMAT_VERSION || (values.size - 1) % 2 != 0) return
        clear()
        values.toList().drop(1).chunked(2).forEach { row ->
            val state = display(row[0]) ?: return@forEach
            val flags = row[1]
            state.phase = phaseOf(flags)
            state.overviewVisible = flags and OVERVIEW_VISIBLE != 0
            state.overviewReported = flags and OVERVIEW_REPORTED != 0
            state.sawOverview = flags and SAW_OVERVIEW != 0
            state.deadlineAt = now + when (state.phase) {
                Phase.LEAVING -> LEAVE_FADE_DELAY_MS
                Phase.HIDDEN -> HIDDEN_DEADLINE_MS
                Phase.RETURNING -> RETURN_DEADLINE_MS
                Phase.IDLE -> 0L
            }
        }
    }

    private fun reveal(state: Display): Action {
        clearDisplayState(state)
        return Action.SHOW
    }

    private fun clearDisplayState(state: Display) {
        state.phase = Phase.IDLE
        state.overviewVisible = false
        state.overviewReported = false
        state.sawOverview = false
        state.deadlineAt = 0L
        state.unknownQueries = 0
    }

    private fun phaseFlag(phase: Phase): Int = when (phase) {
        Phase.IDLE -> 0
        Phase.LEAVING -> LEAVING
        Phase.HIDDEN -> HIDDEN
        Phase.RETURNING -> RETURNING
    }

    private fun phaseOf(flags: Int): Phase = when {
        flags and RETURNING != 0 -> Phase.RETURNING
        flags and HIDDEN != 0 -> Phase.HIDDEN
        flags and LEAVING != 0 -> Phase.LEAVING
        else -> Phase.IDLE
    }

    private companion object {
        const val FORMAT_VERSION = 4
        const val LEAVING = 1
        const val HIDDEN = 2
        const val RETURNING = 4
        const val OVERVIEW_VISIBLE = 8
        const val SAW_OVERVIEW = 16
        const val OVERVIEW_REPORTED = 32

        /**
         * The fade starts this long after the gesture begins. The bar has left the finger and is
         * already travelling with the application, so it reads as the stock fade rather than as the
         * bar vanishing under the thumb, and it finishes before the platform's display-level copy
         * takes over.
         */
        const val LEAVE_FADE_DELAY_MS = 260L

        /**
         * First check after the bar is hidden. A cancelled swipe reaches HIDDEN without any
         * Overview report, so this early probe is what restores the bar quickly in that case;
         * when Recents really is on screen the probe simply re-arms the longer grace below.
         */
        const val HIDDEN_DEADLINE_MS = 2_500L

        /**
         * Re-check interval while Recents is still visible. The user may browse recent tasks for as
         * long as they like, so this only guards against a stuck "visible" report.
         */
        const val HIDDEN_GRACE_MS = 15_000L

        /** Fallback when the return transition never reports its end. */
        const val RETURN_DEADLINE_MS = 2_500L

        /**
         * Rounds of unanswered platform queries before the bar fails open. Only reached when the
         * native visibility map is unreachable at all.
         */
        const val UNKNOWN_QUERY_LIMIT = 2
    }
}
