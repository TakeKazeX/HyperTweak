package com.takekazex.hypertweak.hook.rules.systemui.gesturebar

import android.os.SystemClock
import com.takekazex.hypertweak.util.logging.DebugLog

/** Event-to-policy and action dispatch; queries, drawing, and registration have separate owners. */
internal class GestureBarEvents(
    private val state: GestureBarState,
    private val deadlines: GestureBarDeadlines,
    private val active: () -> Boolean,
    private val queryOverviewVisible: (Int) -> Boolean?,
    private val applyCurrent: () -> Unit,
    private val refreshHome: (String) -> Unit,
    private val animate: (Int, Boolean) -> Unit,
    private val hide: (Int) -> Unit,
) {
    private companion object { const val TAG = "HideBottomBar" }
    /** Overview visibility for one display. Returns whether the value changed the session. */
    fun onOverview(display: Int, visible: Boolean): Boolean {
        if (state.hasOverviewReport(display) && state.isOverviewOnScreen(display) == visible) return false
        val action = runCatching {
            state.onOverview(display, visible, SystemClock.uptimeMillis())
        }.onFailure { DebugLog.w(TAG, "failed to apply overview visibility", it) }.getOrNull()
            ?: return false
        applyAction(display, action)
        applyCurrent()
        // The desktop decision must yield to Recents: HYOS keeps the launcher task on top while
        // the recent-task list is open, so only this report can tell the two surfaces apart.
        refreshHome("overview=$visible")
        return true
    }

    fun onAttachment(display: Int, attached: Boolean) {
        runCatching {
            val previousDeadline = state.deadlineFor(display)
            val action = state.onAttachment(display, attached, SystemClock.uptimeMillis())
            if (action == GestureBarState.Action.NONE && state.deadlineFor(display) == previousDeadline) return
            // The bar is still drawn while leaving; only the transition end fades it out.
            applyAction(display, action)
        }.onFailure { DebugLog.w(TAG, "failed to apply navigation attachment", it) }
        DebugLog.d(TAG, "attachment attached=$attached display=$display " +
            "suppressed=${state.isSuppressed(display)}")
    }

    fun onTransitionFinished(display: Int) {
        if (!active() || !state.isTracked(display)) return
        runCatching {
            // Query only the cancelled-swipe fallback; authenticated Overview owns other sessions.
            val overview = if (state.needsTransitionQuery(display)) queryOverviewVisible(display) else null
            val action = state.onTransitionFinished(display, overview)
            if (action != GestureBarState.Action.NONE) {
                DebugLog.d(TAG, "transition finished display=$display overview=$overview " +
                    "suppressed=${state.isSuppressed(display)} action=$action")
                applyAction(display, action)
            }
        }.onFailure { DebugLog.w(TAG, "failed to restore the gesture bar", it) }
    }

    fun onDeadline(display: Int) {
        if (!active()) return
        runCatching {
            // Ask the platform rather than assuming: null means the native map is unreachable.
            val overview = queryOverviewVisible(display)
            val action = state.onDeadline(display, SystemClock.uptimeMillis(), overview)
            DebugLog.d(TAG, "deadline display=$display overview=$overview " +
                "suppressed=${state.isSuppressed(display)} action=$action")
            if (action == GestureBarState.Action.NONE) {
                // Still genuinely in Recents (or waiting on an answer): keep the guard alive.
                deadlines.scheduleDeadline(display)
                return@runCatching
            }
            applyAction(display, action)
        }.onFailure { DebugLog.w(TAG, "failed to release the gesture bar deadline", it) }
    }

    fun applyAction(display: Int, action: GestureBarState.Action) {
        when (action) {
            GestureBarState.Action.FADE_OUT -> {
                deadlines.scheduleDeadline(display)
                animate(display, true)
                DebugLog.d(TAG, "gesture bar fading out display=$display")
            }
            GestureBarState.Action.HIDE_NOW -> {
                // The platform restored the token at display level and fades its own copy in
                // there. Anything drawn here is that flash at the bottom of Recents, so stop
                // painting immediately instead of fading.
                deadlines.scheduleDeadline(display)
                hide(display)
                DebugLog.d(TAG, "gesture bar suppressed display=$display")
            }
            GestureBarState.Action.SHOW -> {
                deadlines.cancelDeadline(display)
                animate(display, false)
                DebugLog.i(TAG, "gesture bar restored display=$display")
            }
            GestureBarState.Action.NONE -> deadlines.scheduleDeadline(display)
        }
    }

}
