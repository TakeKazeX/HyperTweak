package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherHomePolicyTest {
    private val homes = setOf("com.miui.home/com.miui.home.launcher.Launcher")

    @Test fun `home component matches only the declared launcher entry`() {
        assertEquals(
            "com.miui.home/com.miui.home.launcher.Launcher",
            LauncherHomePolicy.flatten("com.miui.home", "com.miui.home.launcher.Launcher")
        )
        assertTrue(LauncherHomePolicy.matchesHome(
            "com.miui.home/com.miui.home.launcher.Launcher", homes))
        // A sibling launcher activity (settings, recents host, secondary display) is not Home.
        assertFalse(LauncherHomePolicy.matchesHome(
            "com.miui.home/com.miui.home.recents.RecentsActivity", homes))
        assertFalse(LauncherHomePolicy.matchesHome("com.miui.home/.launcher.Launcher", homes))
        assertFalse(LauncherHomePolicy.matchesHome(null, homes))
    }

    @Test fun `desktop suppresses only while the launcher task owns the display`() {
        val home = LauncherHomeProbe(topTaskIsHome = true, keyguardLocked = false)
        assertTrue(LauncherHomePolicy.resolve(home, overviewOnScreen = false, previous = false))
        val app = LauncherHomeProbe(topTaskIsHome = false, keyguardLocked = false)
        assertFalse(LauncherHomePolicy.resolve(app, overviewOnScreen = false, previous = true))
    }

    @Test fun `keyguard and recents always win over the desktop answer`() {
        // The launcher task is still the newest one behind the lock screen; the bar is stock there.
        assertFalse(LauncherHomePolicy.resolve(
            LauncherHomeProbe(topTaskIsHome = true, keyguardLocked = true),
            overviewOnScreen = false, previous = true))
        // HYOS runs Recents inside the launcher activity, so the newest task keeps being Home.
        assertFalse(LauncherHomePolicy.resolve(
            LauncherHomeProbe(topTaskIsHome = true, keyguardLocked = false),
            overviewOnScreen = true, previous = true))
    }

    @Test fun `an unanswerable platform keeps the previous decision`() {
        // Both shapes of "no answer" must not flip the bar: a missing probe and a null top task.
        assertTrue(LauncherHomePolicy.resolve(null, overviewOnScreen = false, previous = true))
        assertFalse(LauncherHomePolicy.resolve(null, overviewOnScreen = false, previous = false))
        val unanswered = LauncherHomeProbe(topTaskIsHome = null, keyguardLocked = false)
        assertTrue(LauncherHomePolicy.resolve(unanswered, overviewOnScreen = false, previous = true))
        assertFalse(LauncherHomePolicy.resolve(unanswered, overviewOnScreen = false, previous = false))
    }

    @Test fun `an answered probe replaces the previous decision in both directions`() {
        assertTrue(LauncherHomePolicy.resolve(
            LauncherHomeProbe(topTaskIsHome = true, keyguardLocked = false),
            overviewOnScreen = false, previous = false))
        assertFalse(LauncherHomePolicy.resolve(
            LauncherHomeProbe(topTaskIsHome = false, keyguardLocked = false),
            overviewOnScreen = false, previous = true))
    }
}
