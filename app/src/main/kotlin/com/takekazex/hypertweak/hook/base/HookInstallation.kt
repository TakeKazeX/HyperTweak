package com.takekazex.hypertweak.hook.base

/** Readiness only; the owning BaseHooker continues to own every installed handle. */
internal class HookInstallation(private val failure: (Throwable) -> Unit) {
    @Volatile var isInstalled = false
        private set
    @Volatile var lastFailure: Throwable? = null
        private set

    @Synchronized fun install(action: () -> Unit) {
        if (isInstalled) return
        try {
            action()
            lastFailure = null
            isInstalled = true
        } catch (error: Throwable) {
            lastFailure = error
            runCatching { failure(error) }
        }
    }

    @Synchronized fun reset() { isInstalled = false; lastFailure = null }
}
