package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandPresentation
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummaryIslandScheduler
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummarySwipePolicy
import com.takekazex.hypertweak.util.logging.DebugLog

/** Scheduler settlement, slot leasing, summary exchange and restoration. */
internal class SettlementCoordinator(private val runtime: IslandSessionState) : SettlementActions {
    private val host get() = runtime.host

    override fun reconcileNative(session: IslandSession, refresh: Boolean): Boolean {
        val owner = session.owner
        if (runtime.retiring() || session.lifecycle.suspended != 0 || session.lifecycle.reconciling ||
            host.inConfigRebuild(owner) || host.current(owner, IslandSlot.EXPANDED) != null ||
            host.showOnce(owner) != null) return false
        val bigHandler = host.handler(owner, IslandSlot.BIG) ?: return false
        val smallHandler = host.handler(owner, IslandSlot.SMALL) ?: return false
        if (host.tempShow(bigHandler) != null) return false
        val big = host.current(owner, IslandSlot.BIG)
        val small = host.current(owner, IslandSlot.SMALL)
        // Native Empty markers belong in the hidden queue, never in a published summary
        // slot. Testing only state==null misses the marker before its lifecycle clears state.
        fun broken(view: Any?) = view != null && host.key(view) == null
        val queue = host.queue(owner)
        val pool = (host.contents(owner) + runtime.summaries(session) + queue).distinct()
            .filter { host.key(it) != null && host.stateKind(it) != IslandStateKind.OTHER }
            .distinctBy(host::key)
        if (pool.isEmpty()) return false
        val represented = (listOfNotNull(big, small) + queue + session.slot.extras).mapNotNull(host::key).toSet()
        val orphaned = pool.filter { host.key(it) !in represented }
        val corruptedQueue = queue.any { host.key(it) == null && !host.isEmptyState(host.state(it)) }
        val refill = broken(big) || broken(small) ||
            big == null && small != null || refresh && big == null && small == null
        if (!refill && orphaned.isEmpty() && !corruptedQueue) return false
        session.lifecycle.reconciling = true
        try {
            // Older module measurements may already have cleared Empty or changed it to
            // Hidden. Those keyless entries are not valid native queue markers anymore.
            if (queue.removeAll { host.key(it) == null && !host.isEmptyState(host.state(it)) }) {
                host.handler(owner, IslandSlot.HIDDEN)?.let { host.setCurrent(it, queue.firstOrNull()) }
            }
            // Release invalid visible-slot pointers, not queue ordering markers. Native
            // refill owns promotion, queue order and state transitions.
            if (broken(big)) host.setCurrent(bigHandler, null)
            if (broken(small)) host.setCurrent(smallHandler, null)
            if (refill) host.fillNative(owner,
                if (big != null && !broken(big)) IslandSlot.SMALL else IslandSlot.BIG)
            orphaned.forEach { host.admitNative(owner, it) }
            DebugLog.i(TAG, "native ownership reconciled tasks=${pool.size} orphaned=${orphaned.size} refill=$refill")
            return true
        } finally {
            session.lifecycle.reconciling = false
        }
    }

    override fun commitSwipe(session: IslandSession, before: List<Any>, change: SummaryIslandScheduler.Change) {
        if (!runtime.nativeSlotsReady(session)) return
        val queue = host.queue(session.owner)
        val pool = (before + queue).distinct()
        // Resolve everything before mutating: a task removed by a racing host update leaves
        // the native carousel untouched instead of failing the whole session.
        val after = change.visible.map { wanted ->
            pool.firstOrNull { runtime.key(it) == wanted } ?: return discardSwipe(change, "visible $wanted")
        }
        val target = pool.firstOrNull { runtime.key(it) == change.task } ?: return discardSwipe(change, "task")
        val bigHandler = host.handler(session.owner, IslandSlot.BIG) ?: return discardSwipe(change, "big handler")
        val smallHandler = host.handler(session.owner, IslandSlot.SMALL) ?: return discardSwipe(change, "small handler")
        val handler = host.handler(session.owner, IslandSlot.HIDDEN) ?: return discardSwipe(change, "hidden handler")
        val replaced = if (change.reveal) change.displaced else change.task
        val index = before.indexOfFirst { runtime.key(it) == replaced }
        if (index > 0 && before.size == after.size) {
            session.seats.replace(replaced, runtime.key(after[index]))
        }
        session.scheduler.commit(change)
        queue.removeAll(before.toSet() + after.toSet())
        val removed = before.filter { it !in after }
        removed.forEach { if (it !in queue) queue.add(it) }
        after.drop(2).asReversed().forEach { queue.add(0, it) }
        host.setCurrent(bigHandler, after.getOrNull(0))
        host.setCurrent(smallHandler, after.getOrNull(1))
        (before + after + target).distinct().forEach { view ->
            val slot = when (after.indexOf(view)) {
                0 -> IslandSlot.BIG
                1 -> IslandSlot.SMALL
                else -> IslandSlot.HIDDEN
            }
            host.addState(handler, view, host.nextState(view, slot))
        }
        removed.forEach { host.enqueueHidden(handler, it) }
        host.setCurrent(handler, queue.firstOrNull())
    }

    private fun discardSwipe(change: SummaryIslandScheduler.Change, missing: String) {
        DebugLog.w(TAG, "summary swipe discarded; $missing is no longer available change=$change")
    }

    override fun selectSummaries(session: IslandSession): List<Any> {
        if (!runtime.nativeSlotsReady(session)) return emptyList()
        val owner = session.owner
        val queue = host.queue(owner)
        val handler = host.handler(owner, IslandSlot.HIDDEN)!!
        val bigHandler = host.handler(owner, IslandSlot.BIG)!!
        val smallHandler = host.handler(owner, IslandSlot.SMALL)!!
        val before = runtime.summaries(session)
        val queued = runtime.queuedSummaries(session)
        val pool = (before + queued).distinct()
        val byKey = pool.mapNotNull { view -> runtime.key(view)?.let { it to view } }.toMap()
        val smallEligible = pool.filter { host.nativeSmallWidth(it) > 0 }.mapNotNull(runtime::key).toSet()
        val selected = session.scheduler.select(
            before.mapNotNull(runtime::key), queued.mapNotNull(runtime::key), smallEligible,
            if (runtime.landscapeMoreEnabled(session)) Int.MAX_VALUE else 3,
        )
        val desired = selected.native.map(byKey::getValue)
        queue.removeAll(desired.toSet())
        host.setCurrent(bigHandler, desired.getOrNull(0))
        host.setCurrent(smallHandler, desired.getOrNull(1))
        (before + desired).distinct().forEach { view ->
            val target = when (desired.indexOf(view)) {
                0 -> IslandSlot.BIG
                1 -> IslandSlot.SMALL
                else -> IslandSlot.HIDDEN
            }
            if (target == IslandSlot.HIDDEN && view !in queue) queue.add(view)
            if (host.stateKind(view) != target.kind()) {
                host.addState(handler, view, host.nextState(view, target))
            }
        }
        host.setCurrent(handler, queue.firstOrNull())
        session.scheduler.retain(
            (queue + desired + listOfNotNull(host.current(owner, IslandSlot.EXPANDED)))
                .mapNotNull(runtime::key).toSet(),
        )
        return selected.extras.map(byKey::getValue)
    }

    override fun claimSmall(session: IslandSession, target: Any) {
        if (!runtime.enabled(session) || target !in session.slot.extras) return
        val handler = host.handler(session.owner, IslandSlot.SMALL) ?: return
        val previous = host.call(handler, "getCurrent") ?: return
        host.setCurrent(handler, session.slot.slot.exchange(target, previous))
        session.seats.assign(runtime.key(target), runtime.key(previous))
        host.updateTouchRegion(session.owner)
    }

    override fun returnToQueue(session: IslandSession) {
        val extras = session.slot.extras
        if (extras.isEmpty()) return
        extras.forEach { extra ->
            runtime.smallX(session, extra)?.let { session.interaction.departingPositions[extra] = it }
            host.lendToHidden(extra)
        }
        val queue = host.queue(session.owner)
        session.slot.slot.lend(queue)
        host.handler(session.owner, IslandSlot.HIDDEN)?.let { host.setCurrent(it, queue.firstOrNull()) }
    }

    override fun settle(tx: SchedulingTransaction) {
        tx.settled = true
        val session = tx.session
        reconcileNative(session)
        runtime.diagnostics.register(session)
        val owner = session.owner
        val queue = host.queue(owner)
        val expanded = host.current(owner, IslandSlot.EXPANDED)
        session.presentation.expanded = expanded
        val eligible = runtime.summarySwipeEnabled(session)
        val more = runtime.landscapeMoreEnabled(session)
        session.scheduler.capacityChanged(session.schedulerState.moreCapacity, more)
        session.schedulerState.moreCapacity = more
        if (eligible) {
            session.scheduler.fillExpandedVacancy(
                tx.expandedKey, expanded?.let(runtime::key), tx.visible.mapNotNull(runtime::key),
                (runtime.summaries(session) + runtime.queuedSummaries(session)).mapNotNull(runtime::key),
            )
        }
        val candidates = if (eligible) selectSummaries(session) else emptyList()
        val big = host.current(owner, IslandSlot.BIG)
        session.presentation.big = big
        val right = host.current(owner, IslandSlot.SMALL)
        session.slot.extras = candidates
        val reserve = big != null && (
            (eligible && right != null && candidates.isNotEmpty()) ||
                runtime.rowModeEnabled(session) ||
                (right != null && runtime.phoneEligible(session) && runtime.preferences.threeIslands && runtime.spectrumTarget(right))
            )
        session.seats.assign(right?.let(runtime::key), candidates.firstOrNull()?.let(runtime::key))
        session.scene.layoutIntent = false
        runtime.layoutMeasurement.run {
            if (!reserve) {
                session.scene.layout = null
                session.scene.fullFrames.clear()
                session.scene.musicSmall = null
                session.scene.portraitContent = null
            }
            if (reserve) (listOfNotNull(right) + candidates).forEach {
                runtime.measure(it)
            }
            session.scene.layoutIntent = reserve
            if (big != null) runtime.measure(big)
            if (reserve && session.scene.layout != null) {
                (listOfNotNull(right) + session.slot.extras).forEach {
                    runtime.measure(it)
                }
            }
        }
        val admitted = session.slot.extras
        val unchangedExtras = admitted.filter { view ->
            host.isSmallState(tx.oldStates[view]) && IslandPresentation.keepBorrowedSummary(
                tx.oldFullFrames[view], session.scene.fullFrames[view], view === tx.eventView,
                tx.forcePublication || tx.oldLayout?.screenWidth != session.scene.layout?.screenWidth,
            )
        }.toSet()
        if (admitted.isNotEmpty() && session.scene.layout != null) {
            val hidden = host.handler(owner, IslandSlot.HIDDEN)!!
            session.slot.slot.take(queue, admitted)
            host.setCurrent(hidden, queue.firstOrNull())
            admitted.forEach { view ->
                val state = host.nextState(view, IslandSlot.SMALL)
                if (view in unchangedExtras) {
                    host.setState(view, state)
                    host.setLastState(view, tx.oldStates.getValue(view))
                } else {
                    host.addState(hidden, view, state)
                }
            }
        } else {
            session.slot.extras = emptyList()
        }
        if (DebugLog.isEnabled(android.util.Log.INFO)) {
        val admission = "more=$more visible=${runtime.summaries(session).size} " +
            "candidates=${candidates.size} leased=${session.slot.extras.size} " +
            "queued=${runtime.queuedSummaries(session).size} " +
            "excluded=${runtime.queuedSummaries(session).count { session.scheduler.isHidden(runtime.key(it)) }} " +
            "available=${session.scene.rowAvailable} used=${session.scene.fullFrames.values.sumOf { it.width }}"
        if (session.schedulerState.admissionLog != admission) {
            DebugLog.i(TAG, "admission $admission")
            session.schedulerState.admissionLog = admission
        }
        }
        val extraKey = session.slot.extra?.let { host.key(it) }
        if (extraKey != session.schedulerState.loggedKey) {
            DebugLog.i(TAG, "third native slot key=$extraKey active=${session.slot.extra != null}")
            session.schedulerState.loggedKey = extraKey
        }
        val oldSurvivors = tx.oldStates.keys.filter {
            it in queue || it === big || it === right || it in session.slot.extras
        }
        val geometryChanged = tx.oldLayout != session.scene.layout ||
            tx.oldFullFrames != session.scene.fullFrames
        val changed = listOfNotNull(big, right).filter { view ->
            IslandPresentation.needsPublication(
                tx.oldFullFrames[view], session.scene.fullFrames[view], geometryChanged,
                tx.forcePublication || tx.oldLayout?.screenWidth != session.scene.layout?.screenWidth,
            )
        }.plus(session.slot.extras + oldSurvivors + listOfNotNull(expanded?.takeIf { it in tx.visible }))
            .distinct().filter { runtime.key(it) != null && it !in unchangedExtras }
        val handler = host.handler(owner, IslandSlot.HIDDEN)!!
        changed.forEach { view ->
            runtime.measure(view)
            host.addState(handler, view, null)
            tx.oldStates[view]?.let { host.setLastState(view, it) }
        }
        runtime.spectrum.syncLyricGuard(session)
    }

    override fun clearLayout(session: IslandSession) {
        runtime.spectrum.releaseLyricGuard(session)
        session.spectrum.returnGeometry = null
        session.spectrum.returnTarget = null
        session.spectrum.returnContents = emptyMap()
        session.presentation.expanded = null
        session.presentation.big = null
        session.scene.layout = null
        session.scene.layoutIntent = false
        session.scene.fullFrames.clear()
        session.scene.musicSmall = null
        session.scene.portraitContent = null
        session.scene.displayOrder = emptyList()
        session.scene.contentSizes.clear()
        session.scene.rowAvailable = 0
        session.scene.rowGap = 0
        session.interaction.gesture.begin(null)
        session.scheduler.clear()
        session.seats.clear()
        session.schedulerState.moreCapacity = false
        session.interaction.swipePreview = null
        session.interaction.summaryTracking = null
        session.interaction.departingPositions.clear()
        session.presentation.fullPresentation.clear()
        session.presentation.departures.clear()
        val templates = (session.template.templateWidths.owners() + session.presentation.fullOrigins.keys).distinct()
        templates.forEach { runtime.templates.releaseFullPosition(session, it) }
    }

    override fun restore(session: IslandSession) {
        val extras = session.slot.extras
        val old = extras.associateWith { host.state(it) }
        returnToQueue(session)
        clearLayout(session)
        // With no borrowed task there is no native state to restore. In particular, never
        // flush the host's shared StateHandler publication list during a native transition.
        if (extras.isEmpty()) return
        val handler = host.handler(session.owner, IslandSlot.HIDDEN)
        if (handler == null) {
            host.updateTouchRegion(session.owner)
            return
        }
        (extras + listOfNotNull(
            host.current(session.owner, IslandSlot.BIG),
            host.current(session.owner, IslandSlot.SMALL),
        )).filter { runtime.key(it) != null }.forEach {
            runtime.measure(it)
            host.addState(handler, it, null)
        }
        old.forEach { (view, state) -> host.setLastState(view, state) }
        host.stop(handler)
        host.updateTouchRegion(session.owner)
    }

    override fun swipeCapacity(session: IslandSession, visible: List<Any>): Int {
        val flags = runtime.flags(session)
        if (!flags.landscapeMore || session.scene.fullFrames.isEmpty()) return 3
        val next = runtime.queuedSummaries(session).firstOrNull { it !in visible }
        val width = next?.let {
            if (flags.landscapeFull) session.scene.contentSizes[it]?.width else host.nativeSmallWidth(it)
        }
        return SummarySwipePolicy.capacity(
            moreEnabled = flags.landscapeMore,
            hasNext = next != null,
            nextWidth = width,
            frames = session.scene.fullFrames.values,
            rowGap = session.scene.rowGap,
            rowAvailable = session.scene.rowAvailable,
            visibleCount = visible.size,
        )
    }

    private fun IslandSlot.kind(): IslandStateKind = when (this) {
        IslandSlot.BIG -> IslandStateKind.BIG
        IslandSlot.SMALL -> IslandStateKind.SMALL
        IslandSlot.HIDDEN -> IslandStateKind.HIDDEN
        else -> IslandStateKind.OTHER
    }

    private companion object { const val TAG = "ThreeIslands" }
}
