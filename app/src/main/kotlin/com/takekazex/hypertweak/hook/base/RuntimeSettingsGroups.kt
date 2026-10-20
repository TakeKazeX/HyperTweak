package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.BackGestureSettingsSnapshot
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSettingsPreferencePolicy
import com.takekazex.hypertweak.dock.DockConfig

/** Ownership, process routing and resync authorization have one registry. */
internal object RuntimeSettingsGroups {
    const val ICONS = "systemui.icons"
    const val ISLANDS = "systemui.islands"
    const val SCREENSHOT = "screenshot.frame"
    const val BACK = "gesture.back"
    const val NATIVE = "launcher.native"
    const val DOCK = "launcher.dock"
    const val VOLUME = "system.volume"
    const val XMSF = "xmsf.focus"
    const val GESTURE_BAR = "system.gesture_bar"
    const val GESTURE_BAR_UI = "systemui.gesture_bar"
    const val REVISION = "runtime_settings_revision"
    private val islandKeys = setOf(
        Preferences.KEY_REMOVE_ISLAND_PLACEHOLDER, Preferences.KEY_THREE_ISLANDS, Preferences.KEY_LANDSCAPE_FULL_ISLANDS,
        Preferences.KEY_LANDSCAPE_MORE_ISLANDS, Preferences.KEY_CC_HIDE_SUPER_ISLAND,
        Preferences.KEY_ISLAND_NARROW_TIME_FONT, Preferences.KEY_ISLAND_LIVE_UPDATE_ICON,
        Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST,
        Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST
    )
    val backKeys = BackGestureSettingsSnapshot.KEYS.toSet() + setOf(
        "back_gesture_predictive_packages", "oneui_cross_task_animation", "contextual_search_long_press",
        "gesture_trigger_height_percent", "gesture_trigger_position_percent", "aosp_gesture_hyperos_haptics"
    )
    private val nativeKeys = setOf(Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON, Preferences.KEY_OPENED_FOLDER_COLUMNS,
        Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT,
        Preferences.KEY_NATIVE_RULE_REVISION, Preferences.KEY_RECENTS_CARD_HAPTIC, "prefs_epoch")
    private data class Group(val targets: Set<String>, val owns: (String) -> Boolean)
    private val systemUi = setOf("com.android.systemui")
    private val groups = linkedMapOf(
        ICONS to Group(systemUi) { it != "prefs_epoch" && it !in islandKeys && IconSettingsPreferencePolicy.affectsIcons(it) },
        ISLANDS to Group(systemUi) { it in islandKeys },
        SCREENSHOT to Group(setOf("com.miui.screenshot")) {
            it == Preferences.KEY_SCREENSHOT_AUTO_FRAME || it == Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE
        },
        BACK to Group(systemUi + setOf("android", "com.miui.home")) { it in backKeys },
        NATIVE to Group(systemUi + "com.miui.home") { it in nativeKeys },
        DOCK to Group(setOf("android")) { it == DockConfig.KEY },
        VOLUME to Group(systemUi + "android") { it == Preferences.KEY_VOLUME_KEY_STEP_COUNT || it == Preferences.KEY_VOLUME_KEY_STEP_SCOPE },
        XMSF to Group(setOf("com.xiaomi.xmsf")) { it == Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH },
        // system_server: DisplayPolicy asks for the attach state on every recents transition.
        GESTURE_BAR to Group(setOf("android")) {
            it == Preferences.KEY_GESTURE_BAR_ATTACH_TRANSITION || it == Preferences.KEY_HIDE_GESTURE_BAR_RECENTS
        },
        // SystemUI: HideBottomBarHooker caches its switches in volatile fields, so a committed
        // change must reach it through the dispatcher. Without this route the hooks keep the value
        // captured at install time and the switch only applies after a SystemUI restart.
        GESTURE_BAR_UI to Group(systemUi) {
            it == Preferences.KEY_HIDE_GESTURE_BAR || it == Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT ||
                it == Preferences.KEY_HIDE_GESTURE_BAR_RECENTS || it == Preferences.KEY_GESTURE_BAR_ATTACH_TRANSITION ||
                it == Preferences.KEY_HIDE_GESTURE_BAR_LAUNCHER
        }
    )
    val targets: Set<String> get() = groups.values.flatMap { it.targets }.toSet()
    fun target(process: String, systemServer: Boolean = false): String = if (systemServer || process == "system_server") "android" else process.substringBefore(':')
    fun forTarget(target: String): Set<String> = groups.filterValues { target in it.targets }.keys
    fun forProcess(process: String, systemServer: Boolean = false): Set<String> = forTarget(target(process, systemServer))
    fun owners(key: String): Set<String> = groups.filterValues { it.owns(key) }.keys
    fun owns(key: String): Boolean = owners(key).isNotEmpty()
    fun owns(target: String, key: String): Boolean = groups.values.any { target in it.targets && it.owns(key) }
    fun affected(key: String?): Set<String> = if (key == null || key == "prefs_epoch") groups.keys
        else groups.filterValues { it.owns(key) }.keys
    fun changed(before: Map<String, Any>, after: Map<String, Any>): Set<String> =
        (before.keys + after.keys).filter {
            // Ordering/commit identities are carried by the snapshot, not UI invalidations.
            it != "prefs_epoch" && it != Preferences.KEY_NATIVE_RULE_REVISION && before[it] != after[it]
        }.flatMap(::affected).toSet()
    fun subset(target: String, values: Map<String, Any>): Map<String, Any> = values.filterKeys { owns(target, it) }
}
