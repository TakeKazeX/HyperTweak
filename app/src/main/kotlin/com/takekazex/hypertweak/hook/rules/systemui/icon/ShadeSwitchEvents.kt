package com.takekazex.hypertweak.hook.rules.systemui.icon

/** A host event port; the producer does not construct or depend on the Duo renderer. */
internal class ShadeSwitchEvents {
    private class Binding(val owner: Any, val started: () -> Unit, val finished: (Boolean) -> Unit,
                          val activeProxy: (Any) -> Boolean, val ownsAirplane: (Any) -> Boolean,
                          val requiresMobile: () -> Boolean,
                          val mobile: (MobileSignalState, Boolean) -> Unit)
    private var binding: Binding? = null

    @Synchronized fun bind(owner: Any, started: () -> Unit, finished: (Boolean) -> Unit,
                           activeProxy: (Any) -> Boolean = { false },
                           ownsAirplane: (Any) -> Boolean = { false },
                           requiresMobile: () -> Boolean = { false },
                           mobile: (MobileSignalState, Boolean) -> Unit = { _, _ -> }) {
        binding = Binding(owner, started, finished, activeProxy, ownsAirplane, requiresMobile, mobile)
    }
    @Synchronized fun retire(owner: Any) {
        if (binding?.owner === owner) binding = null
    }
    @Synchronized fun started() { binding?.started?.invoke() }
    @Synchronized fun hasActiveProxy(root: Any): Boolean = binding?.activeProxy?.invoke(root) ?: false
    @Synchronized fun ownsAirplane(root: Any): Boolean = binding?.ownsAirplane?.invoke(root) ?: false
    @Synchronized fun requiresMobileState(): Boolean = binding?.requiresMobile?.invoke() ?: false
    @Synchronized fun onMobileState(state: MobileSignalState, ready: Boolean) {
        binding?.mobile?.invoke(state, ready)
    }
    @Synchronized fun finished(controlCenterVisible: Boolean) {
        binding?.finished?.invoke(controlCenterVisible)
    }
}

internal object SignalHandoverEvents {
    val shade = ShadeSwitchEvents()
}
