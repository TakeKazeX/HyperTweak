package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.view.MotionEvent
import com.takekazex.hypertweak.hook.rules.systemui.island.host.TouchInput
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import io.github.libxposed.api.XposedInterface

/** Window/child touch routing, click ownership and content swipe scoping. */
internal object TouchRoutingBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val host = context.host
        val access = context.access
        val registrar = context.registrar

        registrar.before(access.method(access.window, "dispatchTouchEvent"), "three_islands_touch_route") { param ->
            val event = param.args.firstOrNull() as? MotionEvent ?: return@before
            val owner = host.windowCoordinator(param.thisObject) ?: return@before
            val session = runtime.sessions[owner] ?: return@before
            runtime.boundary.core(session, IslandFeature.TOUCH, "route third island before child dispatch") {
                runtime.gestures.routeWindowTouch(
                    session,
                    TouchInput(host.touchAction(event), host.touchX(event), host.touchY(event)),
                )
            }
        }

        registrar.before(access.method(access.content, "onIslandClick"), "three_islands_click_claim") { param ->
            val session = runtime.sessionForView(param.thisObject) ?: return@before
            runtime.boundary.core(session, IslandFeature.TOUCH, "claim native click target") {
                runtime.gestures.claimNativeClick(session, param.thisObject)
            }
        }

        registrar.before(access.method(access.touch, "performClick"), "three_islands_perform_click") { param ->
            val window = access.touchView.get(param.thisObject) ?: return@before
            val owner = host.windowCoordinator(window) ?: return@before
            val session = runtime.sessions[owner] ?: return@before
            runtime.boundary.core(session, IslandFeature.TOUCH, "deliver click to captured task") {
                if (runtime.gestures.performClick(session)) param.result = null
            }
        }

        registrar.before(access.method(access.window, "collapse"), "three_islands_collapse_gate") { param ->
            val reason = param.args.firstOrNull() as? String ?: return@before
            if (!reason.startsWith("swipe")) return@before
            val owner = host.windowCoordinator(param.thisObject) ?: return@before
            val session = runtime.sessions[owner] ?: return@before
            runtime.boundary.core(session, IslandFeature.TOUCH, "validate expanded gesture release") {
                if (runtime.gestures.shouldBlockCollapse(session, reason)) param.result = null
            }
        }

        registrar.before(access.method(access.coordinator, "dispatchSwipe"), "three_islands_dispatch_swipe") { param ->
            val session = runtime.sessions[param.thisObject] ?: return@before
            if (runtime.gestures.shouldCaptureSwipe(session)) param.args[2] = true
        }

        listOf("onSwipe", "resetSwipe").forEach { name ->
            val method = access.method(access.content, name).also(registrar::deoptimize)
            registrar.runtime(method, "three_islands_content_$name", XposedInterface.Hooker { chain ->
                val view = chain.thisObject ?: return@Hooker chain.proceed()
                val session = runtime.sessionForView(view)
                if (session != null) {
                    var scoped = false
                    runtime.boundary.core(session, IslandFeature.TOUCH, "scope expanded $name") {
                        scoped = runtime.enabled(session) && session.scene.layout != null &&
                            !session.interaction.gesture.allows(runtime.key(view)) &&
                            session.interaction.swipePreview !== view
                    }
                    if (scoped) return@Hooker null
                }
                if (name == "resetSwipe") {
                    val previous = runtime.renderingState.get()
                    runtime.renderingState.set(view)
                    try {
                        return@Hooker chain.proceed()
                    } finally {
                        if (previous == null) runtime.renderingState.remove()
                        else runtime.renderingState.set(previous)
                    }
                }
                if (session != null) {
                    var remapped: Array<Any?>? = null
                    runtime.boundary.core(session, IslandFeature.PRESENTATION, "swipe full native template") {
                        if (runtime.fullSide(session, view)) {
                            val args = chain.args.toTypedArray()
                            for (index in 3..4) {
                                if (access.small.isInstance(args[index])) {
                                    args[index] = access.bigConstructor.newInstance()
                                }
                            }
                            remapped = args
                        }
                    }
                    remapped?.let { return@Hooker chain.proceed(it) }
                }
                chain.proceed()
            })
        }
    }
}
