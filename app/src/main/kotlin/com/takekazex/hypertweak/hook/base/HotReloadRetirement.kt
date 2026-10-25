package com.takekazex.hypertweak.hook.base

/** Reject only before teardown. Once retirement begins, the replacement must take ownership. */
internal object HotReloadRetirement {
    fun run(
        preflight: () -> List<() -> Unit>,
        rejected: (Throwable) -> Unit,
        cleanupFailed: (Throwable) -> Unit,
    ): Boolean {
        val steps = try { preflight() } catch (error: Throwable) {
            rejected(error)
            return false
        }
        val failures = steps.mapNotNull { runCatching(it).exceptionOrNull() }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
            // Diagnostic failures cannot turn a partially retired generation back into an owner.
            runCatching { cleanupFailed(first) }
        }
        return true
    }
}
