package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.Preferences

/** Live island switches; a narrow seam keeps policies and coordinators JVM-testable. */
internal interface IslandSettings {
    val removePlaceholder: Boolean get() = true
    val threeIslands: Boolean
    val landscapeFull: Boolean
    val landscapeMore: Boolean
}

/** Reads island switches through the shared live settings facade. */
internal class IslandPreferences : IslandSettings {
    override val removePlaceholder: Boolean get() = Preferences.getBoolean(Preferences.KEY_REMOVE_ISLAND_PLACEHOLDER, true)
    override val threeIslands: Boolean get() = Preferences.getBoolean(Preferences.KEY_THREE_ISLANDS, false)
    override val landscapeFull: Boolean get() = Preferences.getBoolean(Preferences.KEY_LANDSCAPE_FULL_ISLANDS, false)
    override val landscapeMore: Boolean get() = Preferences.getBoolean(Preferences.KEY_LANDSCAPE_MORE_ISLANDS, false)
}
