package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import android.os.SystemClock
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.diagnostics.IslandGeometryDiagnostics
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.util.logging.DebugLog

/** DUMP-triggered geometry sampling; never mutates session state. */
internal class DiagnosticsCoordinator(private val runtime: IslandSessionState) : DiagnosticsActions {
    private var diagnostics: IslandGeometryDiagnostics? = null
    private var traceUntil = 0L
    private var remaining = 0
    private var sequence = 0
    private var reading = false
    private var frames = false
    private val frameToken = Any()
    override val tracing: Boolean get() = traceUntil != 0L && !reading && remaining > 0 && runtime.isMainThread() &&
        SystemClock.uptimeMillis() < traceUntil

    override fun register(session: IslandSession) {
        val window = runtime.host.windowOf(session.owner) as? View ?: return
        if (diagnostics != null) return
        diagnostics = IslandGeometryDiagnostics(window.context, runtime.main) { intent ->
            val duration = intent?.getIntExtra("trace_ms", 0) ?: 0
            if (duration > 0) {
                frames = intent?.getBooleanExtra("trace_frames", false) == true
                runtime.main.removeCallbacksAndMessages(frameToken)
                traceUntil = SystemClock.uptimeMillis() + duration.coerceIn(1000, 60000)
                remaining = 500
                sequence = 0
                DebugLog.i("IslandAnimationProbe", "armed durationMs=${duration.coerceIn(1000, 60000)} maxEvents=500")
            }
            val orientation = window.resources.configuration.orientation
            runtime.sessions.snapshot().forEach { current ->
                DebugLog.i(
                    "IslandGeometry",
                    "layout=${current.scene.layout} portrait=${current.scene.portraitContent} " +
                        "frames=${current.scene.fullFrames.values} failed=${current.health.failed} " +
                        "intent=${current.scene.layoutIntent} eligible=${runtime.phoneEligible(current)} " +
                        "orientation=$orientation",
                )
                val host = runtime.host
                val slots = IslandSlot.entries.joinToString { slot ->
                    val view = host.current(current.owner, slot)
                    "$slot=${view?.let(System::identityHashCode)}:state=${view?.let(host::state)}:task=${view?.let(host::key) != null}"
                }
                val contents = host.contents(current.owner)
                DebugLog.i("IslandGeometry", "inventory=${contents.size} queue=${host.queue(current.owner).size} slots=[$slots]")
                (contents + host.queue(current.owner)).distinct().forEachIndexed { index, view ->
                    DebugLog.i("IslandGeometry", "content=$index id=${System.identityHashCode(view)} task=${host.key(view) != null} " +
                        "state=${host.state(view)} last=${host.call(view, "getLastState")}")
                    IslandGeometryDiagnostics.tree("content$index", view as? View)
                }
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

    override fun trace(session: IslandSession, view: Any?, event: String, details: String) {
        if (!tracing) return
        if (!frames && (event.startsWith("properties.") || event.startsWith("frame+"))) return
        reading = true
        try {
            remaining--
            val host = runtime.host
            fun id(value: Any?) = value?.let { System.identityHashCode(it).toString() } ?: "none"
            fun frame(value: Any?): String {
                val v = value as? View ?: return "none"
                val location = IntArray(2).also(v::getLocationOnScreen)
                return "id=${id(v)} box=${v.left},${v.top},${v.width},${v.height}" +
                    " screen=${location[0]},${location[1]} clip=${v.clipBounds}" +
                    " xy=${v.x},${v.y} t=${v.translationX},${v.translationY}" +
                    " scale=${v.scaleX},${v.scaleY} pivot=${v.pivotX},${v.pivotY} a=${v.alpha} vis=${v.visibility}"
            }
            val owner = session.owner
            val slots = IslandSlot.entries.joinToString { "$it=${id(host.current(owner, it))}" }
            val geometry = if (view != null) {
                "state=${host.state(view)} last=${host.call(view, "getLastState")}" +
                    " x=${host.call(view, "getCurrentBigIslandX", false)}/${host.call(view, "getCurrentBigIslandX", true)}" +
                    " w=${host.call(view, "getCurrentBigIslandWidth", false)}/${host.call(view, "getCurrentBigIslandWidth", true)}" +
                    " gap=${host.call(view, "getSpace")} smallWidth=${host.nativeSmallWidth(view)}" +
                    " root[${frame(view)}] parent[${frame((view as? View)?.parent)}] big[${frame(host.bigIslandView(view))}]" +
                    " small[${frame(host.call(view, "getSmallIslandView"))}] fake[${frame(host.fakeView(view))}]"
            } else ""
            DebugLog.i("IslandAnimationProbe", "n=${++sequence} ms=${SystemClock.uptimeMillis()} event=$event view=${id(view)}" +
                " three=${runtime.preferences.threeIslands} full=${runtime.preferences.landscapeFull}" +
                " more=${runtime.preferences.landscapeMore} compact=${runtime.preferences.removePlaceholder}" +
                " flags=${runtime.flags(session)} slots=[$slots] extras=${session.slot.extras.size}" +
                " $details $geometry")
        } catch (error: Throwable) {
            DebugLog.w("IslandAnimationProbe", "probe snapshot failed event=$event", error)
        } finally { reading = false }
    }

    override fun sampleAnimation(session: IslandSession, view: Any) {
        if (!tracing || !frames) return
        val weakView = java.lang.ref.WeakReference(view)
        for (delay in listOf(0L, 16L, 48L, 96L, 160L, 320L, 640L)) {
            runtime.main.postAtTime({ weakView.get()?.let { trace(session, it, "frame+$delay") } },
                frameToken, SystemClock.uptimeMillis() + delay)
        }
    }

    override fun retire() {
        traceUntil = 0L
        remaining = 0
        runtime.main.removeCallbacksAndMessages(frameToken)
        diagnostics?.retire()
        diagnostics = null
    }
}
