package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandPresentation
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummaryIslandScheduler
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummarySwipePolicy
import com.takekazex.hypertweak.util.DebugLog

/** Scheduler settlement, slot leasing, summary exchange and restoration. */
internal class SettlementCoordinator(private val runtime: IslandRuntime) {
    private val host get() = runtime.host

    fun commitSwipe(session: IslandSession, before: List<Any>, change: SummaryIslandScheduler.Change) {
        val queue = host.queue(session.owner)
        val pool = (before + queue).distinct()
        val after = change.visible.map { wanted -> pool.single { runtime.key(it) == wanted } }
        val target = pool.single { runtime.key(it) == change.task }
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
        host.setCurrent(host.handler(session.owner, IslandSlot.BIG)!!, after.getOrNull(0))
        host.setCurrent(host.handler(session.owner, IslandSlot.SMALL)!!, after.getOrNull(1))
        val handler = host.handler(session.owner, IslandSlot.HIDDEN)!!
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

    fun selectSummaries(session: IslandSession): List<Any> {
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

    fun claimSmall(session: IslandSession, target: Any) {
        if (!runtime.enabled(session) || target !in session.slot.extras) return
        val handler = host.handler(session.owner, IslandSlot.SMALL) ?: return
        val previous = host.call(handler, "getCurrent") ?: return
        host.setCurrent(handler, session.slot.slot.exchange(target, previous))
        session.seats.assign(runtime.key(target), runtime.key(previous))
        host.updateTouchRegion(session.owner)
    }

    fun returnToQueue(session: IslandSession) {
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

    fun settle(tx: SchedulingTransaction) {
        tx.settled = true
        val session = tx.session
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
                runtime.rowModeEnabled(session) || runtime.portraitCompactEnabled(session) ||
                (right != null && runtime.preferences.threeIslands && runtime.spectrumTarget(right))
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
                host.call(it, "calculateBigIslandWidth")
            }
            session.scene.layoutIntent = reserve
            if (big != null) host.call(big, "calculateBigIslandWidth")
            if (reserve && session.scene.layout != null) {
                (listOfNotNull(right) + session.slot.extras).forEach {
                    host.call(it, "calculateBigIslandWidth")
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
        val admission = "more=$more visible=${runtime.summaries(session).size} " +
            "candidates=${candidates.size} leased=${session.slot.extras.size} " +
            "queued=${runtime.queuedSummaries(session).size} " +
            "excluded=${runtime.queuedSummaries(session).count { session.scheduler.isHidden(runtime.key(it)) }} " +
            "available=${session.scene.rowAvailable} used=${session.scene.fullFrames.values.sumOf { it.width }}"
        if (session.schedulerState.admissionLog != admission) {
            DebugLog.i(TAG, "admission $admission")
            session.schedulerState.admissionLog = admission
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
            .distinct().filterNot { it in unchangedExtras }
        val handler = host.handler(owner, IslandSlot.HIDDEN)!!
        changed.forEach { view ->
            host.call(view, "calculateBigIslandWidth")
            host.addState(handler, view, null)
            tx.oldStates[view]?.let { host.setLastState(view, it) }
        }
        runtime.spectrum.syncLyricGuard(session)
    }

    fun restore(session: IslandSession) {
        runtime.spectrum.releaseLyricGuard(session)
        session.spectrum.returnGeometry = null
        session.presentation.expanded = null
        session.presentation.big = null
        val extras = session.slot.extras
        val old = extras.associateWith { host.state(it) }
        returnToQueue(session)
        session.scene.layout = null
        session.scene.layoutIntent = false
        session.scene.fullFrames.clear()
        session.scene.musicSmall = null
        session.scene.portraitContent = null
        session.interaction.gesture.begin(null)
        session.scheduler.clear()
        session.interaction.swipePreview = null
        session.interaction.summaryTracking = null
        session.interaction.departingPositions.clear()
        session.template.templateWidths.releaseAll { view -> runtime.nativeCutoutRead.run { host.fixedCutoutWidth(view) } }
        val handler = host.handler(session.owner, IslandSlot.HIDDEN)
        if (handler == null) {
            host.updateTouchRegion(session.owner)
            return
        }
        (extras + listOfNotNull(
            host.current(session.owner, IslandSlot.BIG),
            host.current(session.owner, IslandSlot.SMALL),
        )).forEach {
            host.call(it, "calculateBigIslandWidth")
            host.addState(handler, it, null)
        }
        old.forEach { (view, state) -> host.setLastState(view, state) }
        host.stop(handler)
        host.updateTouchRegion(session.owner)
    }

    fun swipeCapacity(session: IslandSession, visible: List<Any>): Int {
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
