package com.takekazex.hypertweak.hook.rules.systemui.island.policy

/** The host's global manual flag must not exempt a replacement task from its collapse timer. */
internal object IslandExpansionOwnership {
    fun clearInheritedUserExpansion(userExpanded: Boolean, previous: String?, current: String?): Boolean =
        userExpanded && previous != current
}
