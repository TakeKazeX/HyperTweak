package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandRelayoutDriver
import com.takekazex.hypertweak.util.logging.DebugLog

/** Pre-draw relayout: settle once with the new view tree, then publish on the host's path. */
internal class RelayoutCoordinator(private val runtime: IslandSessionState) : RelayoutActions {
    private val host get() = runtime.host

    override fun prepare(session: IslandSession) {
        runtime.settlement.reconcileNative(session, refresh = true)
        if (!runtime.schedulingEnabled(session)) {
            // Placeholder removal owns measurement only. Refresh its fields without publishing
            // a second native handler transaction when three-island scheduling is disabled.
            runtime.settlement.restore(session)
            // Native measurement publishes its own template animation. Suppressing that and
            // replaying updateState later would create a second, unrelated state transition.
            runtime.summaries(session).forEach { runtime.measure(it) }
            return
        }
        val tx = SchedulingTransaction(
            session,
            runtime.summaries(session),
            host.current(session.owner, IslandSlot.EXPANDED)?.let(runtime::key),
        )
        tx.oldStates = session.slot.extras.associateWith { host.state(it) }
        tx.forcePublication = true
        runtime.context.withTransaction(tx) {
            session.interaction.gesture.begin(null)
            runtime.settlement.returnToQueue(session)
            runtime.relayoutPublication.run {
                runtime.settlement.settle(tx)
                host.handler(session.owner, IslandSlot.HIDDEN)?.let(host::stop)
            }
        }
    }

    override fun publish(session: IslandSession) {
        // Geometry measurement cannot restore root alpha/clip/translation after a configuration
        // change. Publish once, after the new layout, in both native and module-owned modes.
        val visible = (runtime.summaries(session) + listOfNotNull(
            host.current(session.owner, IslandSlot.EXPANDED), host.current(session.owner, IslandSlot.SHOW_ONCE),
        )).distinct().filter { runtime.key(it) != null }
        val windowState = visible.firstOrNull()?.let(host::windowState)
        if (windowState == null) {
            host.updateTouchRegion(session.owner)
            return
        }
        val previous = host.configChange(windowState)
        val handler = host.handler(session.owner, IslandSlot.HIDDEN) ?: return
        try {
            host.setConfigChange(windowState, true)
            runtime.relayoutPublication.run {
                visible.forEach { view ->
                    host.addState(handler, view, null)
                    host.setLastState(view, host.state(view))
                }
                // One native batch drives controller state, templates, alpha and clipping.
                // Do not publish a second direct ViewModel callback for those same views.
                host.stop(handler)
            }
        } finally {
            host.setConfigChange(windowState, previous)
        }
        host.updateTouchRegion(session.owner)
        DebugLog.i(TAG, "frame relayout published summaries=${visible.size}")
    }

    override fun request(session: IslandSession) {
        if (runtime.retiring()) return
        val window = host.windowOf(session.owner) as? View ?: return
        runtime.diagnostics.register(session)
        if (session.template.relayout?.window !== window) {
            session.template.relayout?.retire()
            session.template.relayout = IslandRelayoutDriver(
                window,
                ready = {
                    !runtime.retiring() && session.lifecycle.suspended == 0 &&
                        runtime.context.transaction == null && !host.inConfigRebuild(session.owner)
                },
                prepare = { prepare(session) },
                publish = { publish(session) },
                failure = { error ->
                    session.health.fail(IslandFeature.TEMPLATE)
                    DebugLog.e(TAG, "frame relayout failed; restore native geometry", error)
                    runCatching { runtime.settlement.restore(session) }
                        .onFailure { DebugLog.e(TAG, "frame restoration failed", it) }
                },
            )
        }
        session.template.relayout?.request()
    }

    private companion object { const val TAG = "ThreeIslands" }
}
