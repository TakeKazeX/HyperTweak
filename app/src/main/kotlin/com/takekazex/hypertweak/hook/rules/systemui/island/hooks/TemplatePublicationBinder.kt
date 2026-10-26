package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandTemplateCutout
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface

/** Suppresses native `setTo` while an explicit relayout publication is in progress. */
internal object TemplatePublicationBinder {
    fun install(context: IslandHookContext) {
        val access = context.access
        val write = checkNotNull(access.templateWrite)
        write.callers.forEach(context.registrar::deoptimize)
        context.registrar.before(write.method.also(context.registrar::deoptimize),
            "three_islands_template_cutout_write") { param ->
            // The callback can carry widths from an obsolete summary/expanded state. Enforce
            // only this exact builder's leased parent, including the main camera seat.
            runCatching {
                val cutout = write.cutout.get(param.thisObject) as? View ?: return@runCatching
                cutout.parent?.let(IslandTemplateCutout::width)?.let { param.args[0] = it }
            }.onFailure { DebugLog.w("ThreeIslands", "native template cutout write", it) }
        }
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
