package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.graphics.Rect
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature

/** Animation coordinates, hit regions and the side-media width publication. */
internal object CoordinatesBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val host = context.host
        val access = context.access
        val registrar = context.registrar

        registrar.after(access.method(access.coords, "getSmallIslandX"), "three_islands_coord_x") { param ->
            val view = access.coordsView.get(param.thisObject) ?: return@after
            if (!access.content.isInstance(view)) {
                if (runtime.diagnostics.tracing) {
                    val real = host.realContent(view) ?: return@after
                    runtime.sessionForView(real)?.let {
                        runtime.diagnostics.trace(it, real, "coordinate.smallX.fake", "native=${param.result}")
                    }
                }
                return@after
            }
            val session = runtime.sessionForView(view) ?: return@after
            val native = param.result
            runtime.boundary.core(session, IslandFeature.PRESENTATION, "animation coordinates") {
                runtime.coordinates.nativeCoordinateX(session, view)?.let { param.result = it }
            }
            if (runtime.diagnostics.tracing) runtime.diagnostics.trace(session, view, "coordinate.smallX", "native=$native final=${param.result}")
        }

        registrar.after(access.method(access.anim, "getSmallIslandX"), "three_islands_anim_small_x") { param ->
            val session = runtime.context.transaction?.session
                ?: runtime.sessionForAnimation(param.thisObject)
                ?: return@after
            val native = param.result
            runtime.boundary.core(session, IslandFeature.PRESENTATION, "native small target") {
                runtime.coordinates.nativeSmallTargetX(session)?.let { param.result = it }
            }
            if (runtime.diagnostics.tracing) runtime.diagnostics.trace(session, runtime.context.animatingView,
                "controller.smallX", "native=$native final=${param.result}")
        }

        registrar.after(access.method(access.content, "getSmallIslandRect"), "three_islands_small_rect") { param ->
            val view = param.thisObject
            val session = runtime.sessionForView(view) ?: return@after
            runtime.boundary.core(session, IslandFeature.PRESENTATION, "small hit region") {
                val x = runtime.coordinates.smallX(session, view) ?: return@core
                val rect = param.result as? Rect ?: return@core
                val returning = runtime.coordinates.returningFrame(session, view, x)
                rect.set(
                    returning?.x ?: x,
                    rect.top,
                    returning?.let { it.x + it.width } ?: (x + runtime.sideWidth(session, view)),
                    rect.bottom,
                )
            }
        }

        listOf(access.method(access.content, "getSmallIslandViewWidth"),
            access.method(access.fake, "getSmallIslandViewWidth")).forEach { method ->
            registrar.after(method, "three_islands_small_view_width_${method.declaringClass.simpleName}") { param ->
                val real = host.realContent(param.thisObject) ?: return@after
                val session = runtime.sessionForView(real) ?: return@after
                runtime.boundary.soft(session, IslandFeature.SPECTRUM, "publish side media width") {
                    runtime.coordinates.publishedSmallWidth(session, real)?.let { param.result = it }
                }
            }
        }
    }
}
