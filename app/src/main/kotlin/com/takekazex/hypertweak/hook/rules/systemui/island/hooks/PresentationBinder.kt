package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.session.TemplateAnimation
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandMembers
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface

/** Render-only state projection, template alignment and gesture-animator routing. */
internal object PresentationBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val host = context.host
        val access = context.access
        val registrar = context.registrar

        registrar.after(access.method(access.coordinator, "setShadow").also(registrar::deoptimize),
            "three_islands_borrowed_shadow") { param ->
            val session = runtime.sessions[param.thisObject] ?: return@after
            runtime.boundary.core(session, IslandFeature.PRESENTATION, "clear borrowed summary shadows") {
                runtime.presentation.clearBorrowedShadows(session)
            }
        }

        registrar.deoptimize(access.method(access.content, "updateBigIslandLayoutWithAnim"))
        registrar.runtime(
            access.method(access.content, "doBigIslandAnim").also(registrar::deoptimize),
            "three_islands_template_publication",
            XposedInterface.Hooker { chain ->
                val view = chain.args[8] ?: return@Hooker chain.proceed()
                val decision = try {
                    runtime.presentation.bigIslandAnimation(view, chain.args.toTypedArray())
                } catch (error: Throwable) {
                    DebugLog.e("ThreeIslands", "align native template animation", error)
                    TemplateAnimation.Passthrough
                }
                if (runtime.diagnostics.tracing) runtime.sessionForView(view)?.let { session ->
                    val final = (decision as? TemplateAnimation.Redirect)?.args ?: chain.args.toTypedArray()
                    runtime.diagnostics.trace(session, view, "template.animation",
                        "host=${System.identityHashCode(chain.thisObject)} in=${chain.args.take(4)} out=${final.take(4)} decision=${decision.javaClass.simpleName}")
                }
                when (decision) {
                    TemplateAnimation.Suppress -> null
                    TemplateAnimation.Passthrough -> chain.proceed()
                    is TemplateAnimation.Redirect ->
                        if (runtime.relayoutPublication.active) {
                            runtime.templatePublication.run { chain.proceed(decision.args) }
                        } else {
                            chain.proceed(decision.args)
                        }
                }
            },
        )

        registrar.after(
            access.method(access.content, "getFixedCutoutWidth").also(registrar::deoptimize),
            "three_islands_fixed_cutout",
        ) { param ->
            val real = host.realContent(param.thisObject) ?: return@after
            val session = runtime.sessionForView(real)
            runtime.presentation.fixedCutoutWidth(session, real)?.let { param.result = it }
        }

        listOf("onPressSmallIsland", "resetPressSmallIsland").forEach { name ->
            registrar.before(
                access.method(access.gestureAnimator, name).also(registrar::deoptimize),
                "three_islands_press_$name",
            ) { param ->
                val view = host.animationView(param.thisObject) ?: return@before
                val session = runtime.sessionForView(view) ?: return@before
                runtime.boundary.core(session, IslandFeature.PRESENTATION, "press full native template") {
                    val target = runtime.presentation.pressTarget(session, view, name) ?: return@core
                    if (target == "onPressBigIsland") {
                        host.pressBigIsland(param.thisObject, view)
                    } else {
                        host.resetPressBigIsland(param.thisObject, view)
                    }
                    param.result = null
                }
            }
        }

        listOf("getState", "getLastState").forEach { name ->
            registrar.after(
                access.method(access.content, name).also(registrar::deoptimize),
                "three_islands_render_state_$name",
            ) { param ->
                if (runtime.renderingState.get() !== param.thisObject) return@after
                val session = runtime.sessionForView(param.thisObject) ?: return@after
                runtime.boundary.core(session, IslandFeature.PRESENTATION, "native large media state") {
                    runtime.presentation.projectedRenderState(session, param.thisObject, param.result)
                        ?.let { param.result = it }
                }
            }
        }

        registrar.runtime(
            access.method(access.anim, "onStateChange"),
            "three_islands_render_context",
            XposedInterface.Hooker { chain ->
                val view = chain.args.firstOrNull() ?: return@Hooker chain.proceed()
                runtime.context.withAnimating(view) {
                    val session = runtime.sessionForView(view)
                    if (session != null && runtime.diagnostics.tracing) runtime.diagnostics.trace(session, view, "state.before")
                    session?.let { runtime.presentation.beforeStateChange(it, view) }
                    val result = chain.proceed()
                    session?.let { runtime.presentation.afterStateChange(it, view) }
                    if (session != null && runtime.diagnostics.tracing) {
                        runtime.diagnostics.trace(session, view, "state.after")
                        runtime.diagnostics.sampleAnimation(session, view)
                    }
                    result
                }
            },
        )

        // A hidden or deleted full template keeps its lease until this view's exit transition ends.
        registrar.after(access.delegateLifecycle.also(registrar::deoptimize), "three_islands_template_departure") { param ->
            runCatching {
                val view = access.delegateView.get(param.thisObject) ?: return@runCatching
                val session = runtime.sessionForView(view) ?: return@runCatching
                if (session.presentation.departures.isEmpty()) return@runCatching
                val tag = param.args[1] as? String ?: return@runCatching
                val type = (param.args[2] as? Enum<*>)?.name ?: return@runCatching
                runtime.templates.animationEvent(session, view, tag, type)
            }.onFailure { DebugLog.e("ThreeIslands", "track full template departure", it) }
        }

        // A full or side-media template releases its camera-facing padding, but the host still
        // subtracts it from the holder's text budget; without it the timer text overflows by that
        // padding and the holder scales the digits down by a fixed factor.
        access.templateSideWidths.forEach { (leftSide, writer) ->
            registrar.before(writer, "three_islands_template_side_width_${if (leftSide) "left" else "right"}") { param ->
                runCatching {
                    val width = param.args[1] as? Int ?: return@runCatching
                    val padding = runtime.templates.releasedPadding(param.thisObject ?: return@runCatching, leftSide)
                    if (padding > 0) param.args[1] = width + padding
                }.onFailure { DebugLog.e("ThreeIslands", "restore released template padding budget", it) }
            }
        }

        registrar.before(access.method(access.resolver, "resolve"), "three_islands_transition_resolve") { param ->
            val view = runtime.context.animatingView ?: return@before
            val session = runtime.sessionForView(view) ?: return@before
            runtime.boundary.core(session, IslandFeature.PRESENTATION, "resolve full native template") {
                val (before, after) = runtime.presentation.resolveState(
                    session, view, param.args[0], param.args[1],
                )
                param.args[0] = before
                param.args[1] = after
            }
        }
        registrar.after(access.method(access.resolver, "resolve"), "three_islands_transition_probe") { param ->
            if (!runtime.diagnostics.tracing) return@after
            val view = runtime.context.animatingView ?: return@after
            val session = runtime.sessionForView(view) ?: return@after
            runtime.diagnostics.trace(session, view, "transition.resolved",
                "from=${param.args[0]} to=${param.args[1]} transitions=${(param.result as? List<*>)?.map { it?.javaClass?.simpleName }}")
        }

        listOf("getCurrentBigIslandX", "getCurrentBigIslandWidth").forEach { name ->
            registrar.after(access.method(access.content, name), "three_islands_geometry_$name") { param ->
                val session = runtime.sessionForView(param.thisObject) ?: return@after
                runtime.boundary.core(session, IslandFeature.PRESENTATION, "full template geometry") {
                    runtime.presentation.currentBigIslandGeometry(session, param.thisObject, name)
                        ?.let { param.result = it }
                }
            }
        }

        // Explicitly armed, bounded read-only probe of the properties the native animator writes.
        // Verified dispatcher: this exact (IslandAnimProperties)V method calls the container,
        // big, small and expanded writers. The per-part writers share its signature.
        val update = NativeIslandMembers.named(access.updater, "update", listOf(access.properties))
            .also { check(it.returnType == Void.TYPE) }
        val propertyReaders = listOf("getContainerClipStart", "getContainerClipEnd", "getContainerX", "getContainerTransY", "getContainerAlpha",
            "getBigIslandAlpha", "getBigIslandTransX", "getBigIslandTransY", "getSmallIslandAlpha",
            "getSmallIslandTransX", "getSmallIslandTransY", "getExpandedTransX", "getExpandedTransY")
            .associateWith { name -> NativeIslandMembers.named(access.properties, name, emptyList()).also {
                check(it.returnType == Float::class.javaPrimitiveType)
            } }
        registrar.runtime(update.also(registrar::deoptimize), "three_islands_property_probe",
            XposedInterface.Hooker { chain ->
                if (!runtime.diagnostics.tracing) return@Hooker chain.proceed()
                val real = runCatching { chain.thisObject?.let { access.updaterView.get(it) }?.let(host::realContent) }
                    .onFailure { DebugLog.w("IslandAnimationProbe", "read property-update owner", it) }.getOrNull()
                val session = real?.let(runtime::sessionForView)
                if (session != null) {
                    val properties = chain.args.firstOrNull()
                    val values = runCatching { properties?.let { p ->
                        propertyReaders.entries.joinToString { (name, getter) -> "$name=${getter.invoke(p)}" }
                    } }.getOrElse { "unavailable=${it.javaClass.simpleName}" }
                    runtime.diagnostics.trace(session, real, "properties.before", "props=[$values]")
                }
                val result = chain.proceed()
                if (session != null) runtime.diagnostics.trace(session, real, "properties.after")
                result
            })
    }
}
