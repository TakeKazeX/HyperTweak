package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences

// Published on settings acceptance; hook callbacks perform no storage reads.
private val settingRemoveIslandPlaceholder by hookSetting(true) {
    Preferences.getBoolean(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, true)
}
private val settingThreeIslands by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_THREE_ISLANDS, false) }
private val settingLandscapeFullIslands by hookSetting(false) {
    Preferences.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, false)
}
private val settingLandscapeMoreIslands by hookSetting(false) {
    Preferences.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, false)
}

internal fun prepareIslandPreferencesSettings() { settingRemoveIslandPlaceholder }

/** Live island switches; a narrow seam keeps policies and coordinators JVM-testable. */
internal interface IslandSettings {
    val removePlaceholder: Boolean get() = true
    val threeIslands: Boolean
    val landscapeFull: Boolean
    val landscapeMore: Boolean
}

/** Reads island switches through the shared live settings facade. */
internal class IslandPreferences : IslandSettings {
    override val removePlaceholder: Boolean get() = settingRemoveIslandPlaceholder
    override val threeIslands: Boolean get() = settingThreeIslands
    override val landscapeFull: Boolean get() = settingLandscapeFullIslands
    override val landscapeMore: Boolean get() = settingLandscapeMoreIslands
}
