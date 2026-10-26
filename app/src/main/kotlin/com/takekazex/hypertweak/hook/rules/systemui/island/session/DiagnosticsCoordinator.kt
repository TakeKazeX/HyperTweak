package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.diagnostics.IslandGeometryDiagnostics
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.util.logging.DebugLog

/** DUMP-triggered geometry sampling; never mutates session state. */
internal class DiagnosticsCoordinator(private val runtime: IslandSessionState) : DiagnosticsActions {
    private var diagnostics: IslandGeometryDiagnostics? = null

    override fun register(session: IslandSession) {
        val window = runtime.host.windowOf(session.owner) as? View ?: return
        if (diagnostics != null) return
        diagnostics = IslandGeometryDiagnostics(window.context, runtime.main) {
            val orientation = window.resources.configuration.orientation
            runtime.sessions.snapshot().forEach { current ->
                DebugLog.i(
                    "IslandGeometry",
                    "layout=${current.scene.layout} portrait=${current.scene.portraitContent} " +
                        "frames=${current.scene.fullFrames.values} failed=${current.health.failed} " +
                        "intent=${current.scene.layoutIntent} eligible=${runtime.phoneEligible(current)} " +
                        "orientation=$orientation",
                )
                runtime.summaries(current).forEachIndexed { index, view ->
                    DebugLog.i("IslandGeometry", "summary=$index state=${runtime.host.state(view)} last=${runtime.host.call(view, "getLastState")} " +
                        "shade=${runtime.host.call(view, "getShowShade")} fakeShade=${runtime.host.fakeView(view)?.let { runtime.host.call(it, "getShowShade") }} " +
                        "size=${current.scene.contentSizes[view]}")
                    IslandGeometryDiagnostics.tree("summary$index", view as? View)
                    IslandGeometryDiagnostics.tree("left$index",
                        runtime.host.call(view, "getBigIslandAreaLeft") as? View)
                    IslandGeometryDiagnostics.tree("right$index",
                        runtime.host.call(view, "getBigIslandAreaRight") as? View)
                    IslandGeometryDiagnostics.tree("fake$index", runtime.host.fakeView(view) as? View)
                }
            }
        }
    }

    override fun retire() {
        diagnostics?.retire()
        diagnostics = null
    }
}
