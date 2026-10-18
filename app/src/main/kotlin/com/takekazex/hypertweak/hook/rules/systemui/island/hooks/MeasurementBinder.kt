package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import io.github.libxposed.api.XposedInterface

/** Measurement wraps the native calculation; geometry is decided only after it returns. */
internal object MeasurementBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val access = context.access
        val registrar = context.registrar
        registrar.runtime(
            access.method(access.content, "calculateBigIslandWidth").also(registrar::deoptimize),
            "three_islands_measure",
            XposedInterface.Hooker { chain ->
                val view = chain.thisObject
                runtime.context.withMeasuring(view) {
                    val session = view?.let(runtime::sessionForView)
                    if (session != null) {
                        // Restore lyric visibility before the host reads natural content widths
                        // when rotating into full templates (or enabling them at runtime).
                        runtime.spectrum.syncLyricGuard(session)
                        runtime.boundary.core(
                            session, IslandFeature.MEASUREMENT,
                            "restore native template before measurement",
                        ) {
                            if (!runtime.layoutMeasurement.active) {
                                runtime.templates.releaseFullPosition(session, view)
                            }
                        }
                    }
                    chain.proceed()
                }
            },
        )
        registrar.after(
            access.method(access.helper, "calculateBigIslandWidth"),
            "three_islands_measure_result",
        ) { param ->
            val view = runtime.context.measuringView ?: return@after
            val input = param.args[0] ?: return@after
            val result = param.result ?: return@after
            val geometry = runtime.measurement.afterMeasure(
                view, input, result, param.thisObject,
            ) ?: return@after
            param.result = context.host.createResult(geometry)
        }
    }
}
