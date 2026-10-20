package com.takekazex.hypertweak.hook.base

/** All saved states are restored before recovering hosts, including newly introduced features. */
internal object HotReloadHostRecovery {
    fun <H> run(
        hooks: List<H>, states: Map<String, Any?>, name: (H) -> String,
        restore: (H, Any?) -> Unit, recover: (H) -> Unit, failure: (H, Throwable) -> Unit
    ) {
        hooks.forEach { hook ->
            val key = name(hook)
            if (states.containsKey(key)) runCatching { restore(hook, states[key]) }
                .onFailure { failure(hook, it) }
        }
        hooks.forEach { hook ->
            runCatching { recover(hook) }.onFailure { failure(hook, it) }
        }
    }
}
