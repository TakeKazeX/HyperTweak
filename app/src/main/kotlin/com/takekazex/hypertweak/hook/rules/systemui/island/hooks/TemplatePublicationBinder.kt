package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import io.github.libxposed.api.XposedInterface

/** Suppresses native `setTo` while an explicit relayout publication is in progress. */
internal object TemplatePublicationBinder {
    fun install(context: IslandHookContext) {
        val access = context.access
        context.registrar.deoptimize(access.widthTween)
        context.registrar.runtime(access.widthTween, "three_islands_native_width_set_to",
            XposedInterface.Hooker { chain ->
                if (!context.runtime.templatePublication.active) {
                    chain.proceed()
                } else {
                    access.widthSetTo.invoke(chain.thisObject, *chain.args.toTypedArray())
                }
            })
    }
}
