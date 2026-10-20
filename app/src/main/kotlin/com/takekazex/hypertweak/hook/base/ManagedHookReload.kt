package com.takekazex.hypertweak.hook.base

/** Shared lifecycle engine for settings reloads of roots and plugin children. */
internal object ManagedHookReload {
    fun run(
        replacements: List<Pair<BaseHooker, BaseHooker>>,
        reset: (BaseHooker) -> Unit = { it.resetAfterHotReloadPrepared() },
        install: (BaseHooker, BaseHooker) -> Unit = { _, next ->
            next.setHotReloadReplacementHandles(null)
            next.performInit()
            next.updateParentState(true)
        },
        recover: () -> Unit
    ) {
        val handles = replacements.flatMap { it.first.collectManagedHookHandles() }
        HookReloadTransaction.run(
            hooks = replacements,
            capture = { it.first.saveHotReloadState() },
            prepare = { it.first.prepareForHotReload() },
            retire = {
                val failures = handles.mapNotNull { runCatching { it.unhook() }.exceptionOrNull() }
                if (failures.isNotEmpty()) throw failures.first().apply {
                    failures.drop(1).forEach(::addSuppressed)
                }
            },
            reset = { reset(it.first) },
            install = { (old, next) -> install(old, next) },
            restore = { (_, next), state -> next.restoreHotReloadState(state) },
            recover = {
                val failures = replacements.mapNotNull { (_, next) ->
                    runCatching { next.recoverHotReloadHosts() }.exceptionOrNull()
                }.toMutableList()
                runCatching(recover).onFailure(failures::add)
                if (failures.isNotEmpty()) throw failures.first().apply {
                    failures.drop(1).forEach(::addSuppressed)
                }
            }
        )
    }
}
