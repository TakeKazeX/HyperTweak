package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.graphics.Rect
import com.takekazex.hypertweak.hook.rules.systemui.island.host.HostRect
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.InvocationTargetException

/** Keeps the vendor's complete app-return media animation while projecting the shared layout. */
internal object MediaRenderBinder {
    fun install(context: IslandHookContext) {
        val runtime = context.runtime
        val access = context.access
        val registrar = context.registrar
        val contract = context.media ?: return
        contract.callers.forEach(registrar::deoptimize)
        contract.entries.forEach { entry ->
            entry.destination?.let(registrar::deoptimize)
            registrar.runtime(
                entry.method.also(registrar::deoptimize),
                "three_islands_native_media_${entry.method.declaringClass.simpleName}_${entry.method.name}",
                XposedInterface.Hooker { chain ->
                    val view = entry.viewIndex?.let { chain.args[it] }
                        ?: if (access.fake.isInstance(chain.thisObject)) {
                            runCatching { access.call(chain.thisObject!!, "getRealView") }
                                .onFailure { DebugLog.e(TAG, "resolve native media owner", it) }
                                .getOrNull()
                        } else {
                            chain.thisObject
                        }
                    val session = view?.let(runtime::sessionForView) ?: return@Hooker chain.proceed()
                    val project = runtime.presentation.prepareNativeMedia(session, view)
                    runtime.boundary.soft(session, IslandFeature.SPECTRUM, "guard native media return text") {
                        runtime.spectrum.syncLyricGuard(session, view)
                    }
                    try {
                        if (!project) return@Hooker chain.proceed()
                        runtime.renderingState.render(view) {
                            if (entry.destination != null) {
                                try {
                                    entry.destination.invoke(chain.thisObject, *chain.args.toTypedArray())
                                } catch (failure: InvocationTargetException) {
                                    throw failure.targetException
                                }
                            } else {
                                chain.proceed()
                            }
                        }
                    } finally {
                        runtime.boundary.soft(session, IslandFeature.SPECTRUM, "guard native media return text") {
                            runtime.spectrum.syncLyricGuard(session, view)
                        }
                    }
                },
            )
        }
        registrar.after(
            contract.appCloseTarget.also(registrar::deoptimize),
            "three_islands_native_media_return",
        ) { param ->
            if (!access.small.isInstance(param.args[0])) return@after
            val view = param.args[1] ?: return@after
            val session = runtime.sessionForView(view) ?: return@after
            runtime.boundary.soft(session, IslandFeature.SPECTRUM, "native media return destination") {
                val rect = param.result as? Rect ?: return@soft
                runtime.spectrum.rewriteReturnRect(
                    session, view, HostRect(rect.left, rect.top, rect.right, rect.bottom),
                )?.let { rewritten ->
                    param.result = Rect(rewritten.left, rect.top, rewritten.right, rect.bottom)
                }
            }
        }
    }

    private const val TAG = "ThreeIslands"
}
