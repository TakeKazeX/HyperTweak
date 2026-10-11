package com.takekazex.hypertweak.hook.rules.backgesture.hooks.core

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class EndedLauncherOpenWallpaperResetTest {
    @Test
    fun matchesOnlyTheExactWallpaperHomeCommand() {
        val wallpaper = Any()
        val marker = HookRuntimeCore.EndedLauncherOpenWallpaperReset(
            null,
            0,
            null,
            null,
            wallpaper,
            1f,
        )

        assertTrue(marker.matchesCommand(wallpaper, 1f))
        assertFalse(marker.matchesCommand(Any(), 1f))
        assertFalse(marker.matchesCommand(wallpaper, 1.14f))
    }
}
