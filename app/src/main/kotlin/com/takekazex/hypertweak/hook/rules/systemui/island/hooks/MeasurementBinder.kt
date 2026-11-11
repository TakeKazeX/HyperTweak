package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.util.logging.DebugLog
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
        // Read-only: the battery reservation the host just stored is budgeted by what it included.
        registrar.after(access.statusWidths, "three_islands_status_widths") { param ->
            val view = param.thisObject ?: return@after
            runCatching { context.host.statusWidthsPublished(view) }
                .onFailure { DebugLog.e("ThreeIslands", "snapshot published status widths", it) }
        }
        registrar.after(
            access.method(access.helper, "calculateBigIslandWidth"),
            "three_islands_measure_result",
        ) { param ->
            val view = runtime.context.measuringView ?: return@after
            val input = param.args[0] ?: return@after
            val result = param.result ?: return@after
            val claimed = runtime.measurement.afterMeasure(view, input, result, param.thisObject)
            // The scheduling session claims only row and three-seat geometry. Every other view
            // takes the stateless camera-seated rewrite, whatever its current handler.
            val geometry = try {
                runtime.compact.afterMeasure(view, input, result, claimed)
            } catch (error: Throwable) {
                DebugLog.e("ThreeIslands", "publish camera-seated island geometry", error)
                claimed
            } ?: return@after
            param.result = context.host.createResult(geometry)
        }
    }
}
