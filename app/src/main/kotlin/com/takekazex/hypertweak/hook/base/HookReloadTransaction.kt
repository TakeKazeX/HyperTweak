package com.takekazex.hypertweak.hook.base

/** Capture before teardown; finish every participant and host recovery even after one failure. */
internal object HookReloadTransaction {
    fun <H, S> run(
        hooks: List<H>, capture: (H) -> S, prepare: (H) -> Unit, reset: (H) -> Unit,
        install: (H) -> Unit, restore: (H, S) -> Unit, retire: () -> Unit, recover: () -> Unit
    ) {
        val snapshots = hooks.map { it to capture(it) }
        val failures = ArrayList<Throwable>()
        fun attempt(action: () -> Unit) {
            runCatching(action).onFailure(failures::add)
        }
        hooks.asReversed().forEach { hook -> attempt { prepare(hook) } }
        attempt(retire)
        hooks.forEach { hook -> attempt { reset(hook) } }
        hooks.forEach { hook -> attempt { install(hook) } }
        snapshots.forEach { (hook, snapshot) -> attempt { restore(hook, snapshot) } }
        attempt(recover)
        if (failures.isNotEmpty()) {
            val failure = failures.first()
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }
}
