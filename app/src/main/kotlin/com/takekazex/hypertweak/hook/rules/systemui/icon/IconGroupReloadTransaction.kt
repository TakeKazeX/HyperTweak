package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Rebuild against one index contract, with a complete rollback if any native callback fails. */
internal object IconGroupReloadTransaction {
    fun <T> run(
        groups: List<T>, attached: (T) -> Boolean, detach: (T) -> Unit,
        applyOrder: () -> Unit, restoreOrder: () -> Unit, attach: (T) -> Unit
    ) {
        try {
            groups.forEach(detach)
            applyOrder()
            groups.forEach(attach)
        } catch (failure: Throwable) {
            // A native call may mutate membership and then throw; inspect actual membership.
            groups.filter(attached).forEach { group ->
                runCatching { detach(group) }.onFailure(failure::addSuppressed)
            }
            runCatching(restoreOrder).onFailure(failure::addSuppressed)
            groups.forEach { group ->
                if (!attached(group)) runCatching { attach(group) }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }
}
