package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.TouchInput
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummaryIslandScheduler
import com.takekazex.hypertweak.util.DebugLog

/** Window touch routing, click ownership and one-summary carousel gestures. */
internal class GestureCoordinator(private val runtime: IslandSessionState) : GestureActions {
    private val host get() = runtime.host

    override fun routeWindowTouch(session: IslandSession, touch: TouchInput) {
        if (!runtime.enabled(session)) return
        if (touch.action == TouchInput.CANCEL) session.interaction.gesture.cancel()
        if (touch.action != TouchInput.DOWN) return
        session.interaction.swipePreview = null
        session.interaction.summaryTracking = null
        val owner = session.owner
        val expanded = host.current(owner, IslandSlot.EXPANDED)
        val expandedRect = expanded?.let(host::expandedRect)
        if (expandedRect?.contains(touch.x.toInt(), touch.y.toInt()) == true) {
            session.interaction.gesture.begin(runtime.key(expanded), expanded = true)
            return
        }
        session.interaction.gesture.begin(null)
        val smallHit = (session.slot.extras + listOfNotNull(host.current(owner, IslandSlot.SMALL)))
            .firstOrNull { small ->
                val x = runtime.smallX(session, small) ?: return@firstOrNull false
                host.smallRect(small, x)?.contains(touch.x.toInt(), touch.y.toInt()) == true
            }
        if (smallHit != null) {
            session.interaction.gesture.begin(runtime.key(smallHit))
            runtime.settlement.claimSmall(session, smallHit)
        } else {
            val big = host.current(owner, IslandSlot.BIG)
            val rect = big?.let(host::bigRect)
            if (rect?.contains(touch.x.toInt(), touch.y.toInt()) == true) {
                session.interaction.gesture.begin(runtime.key(big))
            }
        }
    }

    override fun claimNativeClick(session: IslandSession, view: Any) {
        runtime.settlement.claimSmall(session, view)
    }

    /** Returns true when the native click result must be consumed. */
    override fun performClick(session: IslandSession): Boolean {
        val clickKey = session.interaction.gesture.clickKey ?: return false
        if (!runtime.enabled(session) || session.scene.layout == null) return false
        val owner = session.owner
        val target = (listOfNotNull(
            host.current(owner, IslandSlot.EXPANDED),
            host.current(owner, IslandSlot.BIG),
            host.current(owner, IslandSlot.SMALL),
        ) + session.slot.extras).firstOrNull { runtime.key(it) == clickKey }
        if (target != null) {
            runtime.settlement.claimSmall(session, target)
            host.call(target, "onIslandClick")
        }
        return true
    }

    override fun shouldBlockCollapse(session: IslandSession, reason: String): Boolean {
        if (!reason.startsWith("swipe")) return false
        if (!runtime.enabled(session) || session.scene.layout == null) return false
        val current = host.current(session.owner, IslandSlot.EXPANDED)?.let(runtime::key)
        return !session.interaction.gesture.mayCollapse(current)
    }

    override fun shouldCaptureSwipe(session: IslandSession): Boolean =
        runtime.enabled(session) && session.scene.layout != null &&
            session.interaction.gesture.expandedKey != null

    override fun handleAppEvent(session: IslandSession, visible: List<Any>, event: Any): Boolean {
        val reveal = when {
            host.isSwipeRight(event) -> true
            host.isSwipeLeft(event) -> false
            else -> return false
        }
        if (!runtime.summarySwipeEnabled(session)) return false
        val owner = session.owner
        if (!session.interaction.gesture.cancelled && session.interaction.gesture.expandedKey == null) {
            val plan = swipePlan(session, visible, reveal)
            DebugLog.i(
                TAG,
                "summary swipe reveal=$reveal down=${session.interaction.gesture.clickKey} " +
                    "visible=${visible.map(runtime::key)} " +
                    "queued=${runtime.queuedSummaries(session).map(runtime::key)} plan=$plan",
            )
            if (plan != null) {
                runtime.settlement.commitSwipe(session, visible, plan)
                session.interaction.swipePreview = null
            }
        }
        session.interaction.swipePreview?.let { host.resetSwipe(it) }
        session.interaction.swipePreview = null
        host.handler(owner, IslandSlot.HIDDEN)?.let(host::stop)
        return true
    }

    override fun handleSwipe(session: IslandSession, x: Float, y: Float): Boolean {
        if (session.interaction.summaryTracking == null && (x != 0f || y != 0f)) {
            session.interaction.summaryTracking = kotlin.math.abs(x) > kotlin.math.abs(y)
        }
        if (session.interaction.summaryTracking != true) return false
        if (!runtime.summarySwipeEnabled(session) || session.interaction.gesture.expandedKey != null) {
            return false
        }
        val owner = session.owner
        val visible = runtime.summaries(session)
        // The host consumed this gesture even when no window is resolvable; keep no preview.
        val window = host.windowOf(owner) ?: return true
        val reveal = host.isSwipeTowardsSmall(window, x)
        val plan = swipePlan(session, visible, reveal)
        val target = plan?.let { change ->
            (visible + host.queue(owner)).firstOrNull { runtime.key(it) == change.task }
        }
        if (session.interaction.swipePreview !== target) {
            session.interaction.swipePreview?.let { host.resetSwipe(it) }
        }
        session.interaction.swipePreview = target
        if (target != null) {
            if (!plan.reveal || visible.isEmpty()) {
                val next = host.nextState(target, if (plan.reveal) IslandSlot.BIG else IslandSlot.HIDDEN)
                host.onSwipe(target, x, y, host.state(target), next)
            }
        }
        return true
    }

    override fun resetSwipe(session: IslandSession): Boolean {
        if (session.interaction.summaryTracking != true) return false
        if (!runtime.summarySwipeEnabled(session) || session.interaction.gesture.expandedKey != null) {
            return false
        }
        session.interaction.swipePreview?.let { host.resetSwipe(it) }
        session.interaction.swipePreview = null
        session.interaction.summaryTracking = null
        return true
    }

    private fun swipePlan(
        session: IslandSession,
        visible: List<Any>,
        reveal: Boolean,
    ): SummaryIslandScheduler.Change? = session.scheduler.plan(
        visible.mapNotNull(runtime::key),
        runtime.queuedSummaries(session).mapNotNull(runtime::key),
        reveal,
        session.interaction.gesture.clickKey,
        runtime.settlement.swipeCapacity(session, visible),
    )

    private companion object { const val TAG = "ThreeIslands" }
}
