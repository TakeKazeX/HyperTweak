package com.takekazex.hypertweak.hook.rules.systemui

/** The platform's answer about what currently owns the bottom of the primary display. */
internal data class LauncherHomeProbe(
    /**
     * Whether the newest visible primary-display task is the launcher's HOME component; null when
     * the platform could not be asked at all.
     */
    val topTaskIsHome: Boolean?,
    /** Whether the keyguard is covering the desktop right now. */
    val keyguardLocked: Boolean
)

/**
 * Decides whether the launcher's Home surface currently owns the primary display.
 *
 * The gesture-bar draw path runs in SystemUI and cannot see Shell's task organizer (that lives in
 * system_server), so the answer comes from the activity manager's running-task list: the newest
 * task of the primary display is the launcher's HOME component. That covers the whole desktop -
 * the app drawer, an opened folder and editing mode are all the same activity.
 *
 * Two surfaces must never inherit that answer:
 * - the keyguard, where the launcher task merely sits underneath a lock screen that owns the bar;
 * - recent tasks, which HYOS implements inside the same launcher activity, so the top task keeps
 *   being Home while Recents is genuinely on screen. Recent-task hiding owns that state.
 *
 * Component names are compared as plain `package/class` strings so the policy stays a pure
 * function the JVM tests can exercise without an Android runtime.
 */
internal object LauncherHomePolicy {
    const val LAUNCHER_PACKAGE = "com.miui.home"

    /** Canonical `package/class` form; a ComponentName flattens to exactly this. */
    fun flatten(packageName: String, className: String): String = "$packageName/$className"

    fun matchesHome(component: String?, homes: Set<String>): Boolean =
        component != null && component in homes

    /**
     * Whether the bar must be hidden because the desktop owns the screen.
     *
     * @param overviewOnScreen whether Recents owns the bottom of the display right now.
     * @param previous the last resolved answer, reused while the platform cannot answer. Keeping it
     *   is deliberate: guessing "not Home" would flash the bar on every unanswerable frame, and
     *   guessing "Home" would hide it over an application.
     */
    fun resolve(
        probe: LauncherHomeProbe?,
        overviewOnScreen: Boolean,
        previous: Boolean
    ): Boolean = when {
        probe == null -> previous
        probe.keyguardLocked -> false
        overviewOnScreen -> false
        probe.topTaskIsHome == null -> previous
        else -> probe.topTaskIsHome
    }
}
