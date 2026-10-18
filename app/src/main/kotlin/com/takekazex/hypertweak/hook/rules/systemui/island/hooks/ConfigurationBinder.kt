package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import io.github.libxposed.api.XposedInterface

/** Configuration rebuilds return our lease before the window reinflates views. */
internal object ConfigurationBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val host = context.host
        context.registrar.runtime(
            context.access.method(context.access.window, "onConfigChanged"),
            "three_islands_configuration",
            XposedInterface.Hooker { chain ->
                val owner = host.windowCoordinator(chain.thisObject ?: return@Hooker chain.proceed())
                    ?: return@Hooker chain.proceed()
                val session = runtime.sessions[owner] ?: return@Hooker chain.proceed()
                session.lifecycle.suspended++
                try {
                    runtime.boundary.core(session, IslandFeature.SCHEDULING, "prepare configuration rebuild") {
                        (runtime.summaries(session) + host.queue(owner) + listOfNotNull(
                            host.current(owner, IslandSlot.EXPANDED),
                            host.current(owner, IslandSlot.SHOW_ONCE),
                        )).distinct().forEach { view ->
                            session.template.configurationBindings.expect(view)
                            host.fakeView(view)?.let(session.template.configurationBindings::expect)
                        }
                        session.template.relayout?.request()
                        runtime.settlement.restore(session)
                    }
                    chain.proceed()
                } finally {
                    session.lifecycle.suspended--
                    context.refreshSettings()
                }
            },
        )
    }
}
