package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import com.takekazex.hypertweak.util.logging.DebugLog

/** Native template ownership: rebinding invalidates our frames and must not be animated over. */
internal object TemplateBindingBinder {
    fun install(context: IslandHookContext) {
        val access = context.access
        val registrar = context.registrar
        val runtime = context.runtime
        listOf(access.method(access.content, "updateBigIslandView"),
            access.method(access.fake, "updateBigIslandView")).forEach(registrar::deoptimize)

        registrar.after(
            access.method(access.content, "initBigIslandView").also(registrar::deoptimize),
            "three_islands_template_init",
        ) { param ->
            runtime.templates.templateBound(param.thisObject)
        }
        registrar.after(
            access.method(access.fake, "setFakeSecondLayout").also(registrar::deoptimize),
            "three_islands_template_fake_rebind",
        ) { param ->
            runCatching { access.call(param.thisObject, "getRealView") }
                .onSuccess { view -> view?.let { runtime.templates.templateBound(it, param.thisObject) } }
                .onFailure { DebugLog.e(TAG, "resolve rebound fake template", it) }
        }
    }

    private const val TAG = "ThreeIslands"
}
