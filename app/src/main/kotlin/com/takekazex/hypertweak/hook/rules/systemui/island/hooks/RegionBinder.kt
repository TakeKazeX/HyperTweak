package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.graphics.Rect
import android.graphics.Region
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature

/** Touch/window regions must include leased summaries and the reveal target. */
internal object RegionBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val host = context.host
        val access = context.access
        val registrar = context.registrar

        listOf("getSmallBigIslandRegion", "computeIslandRegion").forEach { name ->
            registrar.after(access.method(access.coordinator, name), "three_islands_region_$name") { param ->
                val session = runtime.sessions[param.thisObject] ?: return@after
                runtime.boundary.core(session, IslandFeature.TOUCH, "window region") {
                    val region = param.result as? Region ?: return@core
                    if (name == "getSmallBigIslandRegion" && runtime.coordinates.needsRevealTarget(session)) {
                        (access.call(session.owner, "getDefaultIslandTouchRegion") as? Region)?.let {
                            region.op(it, Region.Op.UNION)
                        }
                    }
                    runtime.coordinates.smallRegionViews(session).forEach { small ->
                        val x = runtime.coordinates.smallX(session, small) ?: return@forEach
                        val top = host.number(small, "getIslandViewMarginTop")
                        region.op(
                            Rect(
                                x, top,
                                x + runtime.sideWidth(session, small),
                                top + host.number(small, "getIslandViewHeight"),
                            ),
                            Region.Op.UNION,
                        )
                    }
                }
            }
        }

        listOf("hasIsland", "getBigIsLandAndSmallIsLandList").forEach { name ->
            registrar.after(access.method(access.coordinator, name), "three_islands_lookup_$name") { param ->
                val session = runtime.sessions[param.thisObject] ?: return@after
                runtime.boundary.core(session, IslandFeature.TOUCH, "include third task") {
                    val matching = runtime.coordinates.extrasForLookup(
                        session, name, param.args.firstOrNull() as? String,
                    )
                    val list = param.result as? List<*> ?: return@core
                    if (matching.any { it !in list }) param.result = (list + matching).distinct()
                }
            }
        }

        registrar.after(access.method(access.coordinator, "isBigOrSmallIsland"),
            "three_islands_is_big_or_small") { param ->
            val session = runtime.sessions[param.thisObject] ?: return@after
            if (runtime.coordinates.containsExtra(session, param.args.firstOrNull() ?: return@after)) {
                param.result = true
            }
        }
    }
}
