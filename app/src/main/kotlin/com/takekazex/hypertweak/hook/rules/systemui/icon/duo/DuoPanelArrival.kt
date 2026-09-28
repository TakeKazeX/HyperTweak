package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

/** A settled panel stays settled when a network target or header view is briefly replaced. */
internal class DuoPanelArrival(initiallySettled: Boolean = false) {
    var settled: Boolean = initiallySettled
        private set

    fun observe(progress: Float, visible: Boolean) {
        // Header/background mode changes may report the collapsed endpoint while the panel is
        // still visible. A real reverse drag supplies intermediate fractions (or dismissal).
        if (!visible || progress > 0f && progress < 0.999f) settled = false
    }

    fun arrive() { settled = true }

    fun shadeFinished(controlCenterVisible: Boolean) { settled = controlCenterVisible }
}
