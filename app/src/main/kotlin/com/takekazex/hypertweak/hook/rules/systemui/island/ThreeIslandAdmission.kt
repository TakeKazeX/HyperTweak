package com.takekazex.hypertweak.hook.rules.systemui.island

internal object ThreeIslandAdmission {
    // Expanded is an independent fourth slot. It does not reduce the three summary slots.
    fun reserve(eligible: Boolean, hasCandidate: Boolean, expanded: Boolean, hadLayout: Boolean): Boolean =
        eligible && (hasCandidate || (expanded && hadLayout))
}
