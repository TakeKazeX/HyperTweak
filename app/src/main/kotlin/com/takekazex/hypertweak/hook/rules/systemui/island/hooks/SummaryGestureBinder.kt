package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature

/** Replaces the host two-slot carousel at progress, commit and rollback boundaries. */
internal object SummaryGestureBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val access = context.access
        val registrar = context.registrar

        registrar.before(
            access.method(access.swipe, "handleAppEvent").also(registrar::deoptimize),
            "three_islands_summary_commit",
        ) { param ->
            val tx = runtime.context.transaction ?: return@before
            val event = param.args.firstOrNull() ?: return@before
            val session = tx.session
            runtime.boundary.core(session, IslandFeature.TOUCH, "commit one summary swipe") {
                if (runtime.gestures.handleAppEvent(session, tx.visible, event)) param.result = null
            }
        }

        registrar.before(
            access.method(access.swipe, "handleSwipe").also(registrar::deoptimize),
            "three_islands_summary_track",
        ) { param ->
            val session = runtime.sessionForSwipe(param.thisObject) ?: return@before
            val x = (param.args[0] as? Number)?.toFloat() ?: return@before
            val y = (param.args[1] as? Number)?.toFloat() ?: return@before
            runtime.boundary.core(session, IslandFeature.TOUCH, "track one summary swipe") {
                if (runtime.gestures.handleSwipe(session, x, y)) param.result = null
            }
        }

        registrar.before(
            access.method(access.swipe, "resetSwipe").also(registrar::deoptimize),
            "three_islands_summary_rollback",
        ) { param ->
            val session = runtime.sessionForSwipe(param.thisObject) ?: return@before
            runtime.boundary.core(session, IslandFeature.TOUCH, "rollback one summary swipe") {
                if (runtime.gestures.resetSwipe(session)) param.result = null
            }
        }
    }
}
