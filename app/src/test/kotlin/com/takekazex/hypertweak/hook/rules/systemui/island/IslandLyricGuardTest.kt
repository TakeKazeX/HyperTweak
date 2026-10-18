package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.spectrum.IslandLyricConflict
import com.takekazex.hypertweak.hook.rules.systemui.island.spectrum.IslandLyricGuard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLyricGuardTest {
    @Test fun `only the lyric module's published tags are treated as injected content`() {
        assertTrue(IslandLyricConflict.injectedTag("HYPERLYRIC_LEFT_VIEW"))
        assertTrue(IslandLyricConflict.injectedTag("HYPERLYRIC_RIGHT_VIEW"))
        assertTrue(IslandLyricConflict.injectedTag("HYPERLYRIC_LEFT_VIEW_WRAPPER"))
        assertTrue(IslandLyricConflict.injectedTag("HYPERLYRIC_RIGHT_VIEW_WRAPPER"))
        assertFalse(IslandLyricConflict.injectedTag("island_icon_1"))
        assertFalse(IslandLyricConflict.injectedTag(null))
        assertFalse(IslandLyricConflict.injectedTag(42))
    }

    @Test fun `native album waveform and icon modules are restored as spectrum siblings`() {
        for (name in listOf("island_container_module_icon", "island_icon_1", "island_icon_2", "island_fix_icon")) {
            assertTrue(name, IslandLyricConflict.nativeIconName(name))
        }
        assertFalse(IslandLyricConflict.nativeIconName("island_container_module_text"))
        assertFalse(IslandLyricConflict.nativeIconName("island_progress"))
        assertFalse(IslandLyricConflict.nativeIconName(null))
    }
}
