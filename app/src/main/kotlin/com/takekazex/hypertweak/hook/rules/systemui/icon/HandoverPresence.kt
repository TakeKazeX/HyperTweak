package com.takekazex.hypertweak.hook.rules.systemui.icon

/** A host event port; the producer does not construct or depend on the Duo renderer. */
internal class HandoverPresence {
    private class Binding(val owner: Any, val started: () -> Unit, val finished: (Boolean) -> Unit,
                          val activeProxy: (Any) -> Boolean, val ownsAirplane: (Any) -> Boolean,
                          val ownsSignalHandover: () -> Boolean)
    private var binding: Binding? = null

    @Synchronized fun bind(owner: Any, started: () -> Unit, finished: (Boolean) -> Unit,
                           activeProxy: (Any) -> Boolean = { false },
                           ownsAirplane: (Any) -> Boolean = { false },
                           ownsSignalHandover: () -> Boolean = { false }) {
        binding = Binding(owner, started, finished, activeProxy, ownsAirplane, ownsSignalHandover)
    }
    @Synchronized fun retire(owner: Any) {
        if (binding?.owner === owner) binding = null
    }
    @Synchronized fun started() { binding?.started?.invoke() }
    @Synchronized fun hasActiveProxy(root: Any): Boolean = binding?.activeProxy?.invoke(root) ?: false
    @Synchronized fun ownsAirplane(root: Any): Boolean = binding?.ownsAirplane?.invoke(root) ?: false
    /** Duo draws the network glyphs through the shade switch, so the stacked motion stands down. */
    @Synchronized fun ownsSignalHandover(): Boolean = binding?.ownsSignalHandover?.invoke() ?: false
    @Synchronized fun finished(controlCenterVisible: Boolean) {
        binding?.finished?.invoke(controlCenterVisible)
    }
}
