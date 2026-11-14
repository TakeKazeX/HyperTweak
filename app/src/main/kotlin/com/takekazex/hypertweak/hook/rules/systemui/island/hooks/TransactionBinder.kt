package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.os.Looper
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandExpansionOwnership
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface

/** Host scheduling transaction: slot return, original event, settlement and restoration. */
internal object TransactionBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val host = context.host
        val access = context.access
        val registrar = context.registrar
        val handle = access.method(access.coordinator, "handleEvent")

        registrar.runtime(handle, "three_islands_transaction", XposedInterface.Hooker { chain ->
            runtime.renderingState.native {
                val owner = chain.thisObject ?: return@Hooker chain.proceed()
                if (Looper.myLooper() != Looper.getMainLooper() || runtime.context.transaction != null) {
                    return@Hooker chain.proceed()
                }
                val session = runtime.sessions.getOrCreate(owner)
                if (runtime.diagnostics.tracing) runtime.diagnostics.trace(session, chain.args[1], "event.before",
                    "eventType=${chain.args[0]?.javaClass?.simpleName}")
                // Settling at StateHandler.stop publishes animations synchronously. In native
                // portrait mode the host can still be moving BIG out of EXPANDED at that point;
                // an extra module stop would animate SMALL with an empty BIG and an X of zero.
                if (!runtime.schedulingEnabled(session)) {
                    // Finish the module lease before entering the host event, never settle
                    // inside a native stop callback after the mode has been disabled.
                    if (session.slot.extra != null) runtime.boundary.core(
                        session, IslandFeature.SCHEDULING, "release disabled scheduling",
                    ) { runtime.settlement.restore(session) }
                    val result = runtime.context.withNativeSession(session) { chain.proceed() }
                    if (runtime.diagnostics.tracing) runtime.diagnostics.trace(session, chain.args[1], "event.after.native")
                    return@Hooker result
                }
                val previousExpanded = host.current(owner, IslandSlot.EXPANDED)?.let(runtime::key)
                val wasUserExpanded = host.userExpanded(owner)
                val tx = SchedulingTransaction(
                    session, runtime.summaries(session), previousExpanded, chain.args[1],
                )
                tx.oldStates = session.slot.extras.associateWith { host.state(it) }
                runtime.context.withTransaction(tx) {
                    try {
                        runtime.boundary.core(session, IslandFeature.SCHEDULING, "snapshot small origins") {
                            (session.slot.extras + listOfNotNull(host.current(owner, IslandSlot.SMALL))).forEach { view ->
                                runtime.smallX(session, view)?.let { session.interaction.departingPositions[view] = it }
                            }
                        }
                        runtime.boundary.core(session, IslandFeature.SCHEDULING, "return third slot to host") {
                            runtime.settlement.returnToQueue(session)
                        }
                        val result = chain.proceed()
                        runtime.boundary.core(session, IslandFeature.SCHEDULING, "settle event") {
                            if (!tx.settled) {
                                runtime.settlement.settle(tx)
                                host.handler(owner, IslandSlot.HIDDEN)?.let(host::stop)
                            }
                            val currentExpanded = host.current(owner, IslandSlot.EXPANDED)?.let(runtime::key)
                            if (IslandExpansionOwnership.clearInheritedUserExpansion(
                                    wasUserExpanded, previousExpanded, currentExpanded,
                                )) {
                                host.setUserExpanded(owner, false)
                                DebugLog.i(TAG, "automatic expansion replaced manual owner; native collapse timer released")
                            }
                            host.updateTouchRegion(owner)
                        }
                        result
                    } catch (failure: Throwable) {
                        session.health.fail(IslandFeature.SCHEDULING)
                        tx.settled = true
                        runCatching { runtime.settlement.restore(session) }
                            .onFailure { DebugLog.e(TAG, "restore after host failure", it) }
                        throw failure
                    }
                }
            }
        })

        registrar.before(access.method(access.handler, "stop"), "three_islands_select_third_slot") { param ->
            if (Looper.myLooper() != Looper.getMainLooper()) return@before
            val tx = runtime.context.transaction
            if (tx == null) {
                // EventCoordinator publishes through AppStateHandler, not one of the five
                // island slots. Event ownership must cover that entire native call graph.
                val session = runtime.sessionForPublication(param.thisObject) ?: return@before
                runtime.boundary.core(session, IslandFeature.SCHEDULING, "reconcile native publication") {
                    runtime.settlement.reconcileNative(session)
                }
                return@before
            }
            runtime.boundary.core(tx.session, IslandFeature.SCHEDULING, "select third slot") {
                if (!tx.settled) runtime.settlement.settle(tx)
            }
        }

        registrar.after(access.method(access.handler, "addState"), "three_islands_preserve_origin") { param ->
            val tx = runtime.context.transaction ?: return@after
            val view = param.args.firstOrNull() ?: return@after
            val old = tx.oldStates[view] ?: return@after
            val session = tx.session
            runtime.boundary.core(session, IslandFeature.SCHEDULING, "preserve transition origin") {
                host.setLastState(view, old)
            }
        }
    }

    private const val TAG = "ThreeIslands"
}
