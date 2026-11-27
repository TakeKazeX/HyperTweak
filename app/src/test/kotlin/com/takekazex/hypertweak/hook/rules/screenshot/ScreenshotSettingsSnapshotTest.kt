package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys


import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsSnapshot

import org.junit.Assert.*
import org.junit.Test

class ScreenshotSettingsSnapshotTest {
    private val enabledKey = ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME
    private val modeKey = ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE

    @Test fun `malformed types revisions and modes cannot become runtime settings`() {
        assertNull(ScreenshotSettingsSnapshot.read(0, emptyMap<String, Any>()))
        assertNull(ScreenshotSettingsSnapshot.read(1, mapOf(enabledKey to "true")))
        assertNull(ScreenshotSettingsSnapshot.read(1, mapOf(modeKey to 2)))
        assertNull(ScreenshotSettingsSnapshot.read(1, mapOf(modeKey to 1L)))
        assertEquals(ScreenshotSettingsSnapshot(1, true, 1, copyToClipboard = false, picturesDirectory = false),
            ScreenshotSettingsSnapshot.read(1, mapOf(enabledKey to true, modeKey to 1)))
    }
}
