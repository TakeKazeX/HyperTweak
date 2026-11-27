package com.takekazex.hypertweak.settings.contract

import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys


/** Complete, acknowledged screenshot settings; revisions survive app restarts within a boot. */
internal data class ScreenshotSettingsSnapshot(
    val revision: Long,
    val enabled: Boolean,
    val editorMode: Int,
    /** Defaults to off so a snapshot built without it never enables a save side effect. */
    val copyToClipboard: Boolean = false,
    val picturesDirectory: Boolean = false
) {
    companion object {
        const val REVISION = "screenshot_settings_revision"
        fun read(revision: Long, values: Map<String, *>): ScreenshotSettingsSnapshot? {
            if (revision <= 0) return null
            val enabled = values[ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME] ?: false
            val mode = values[ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE] ?: 0
            // Absent means off: a host generation without the saved value must not copy.
            val copy = values[ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD] ?: false
            val pictures = values[ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY] ?: false
            if (pictures !is Boolean || enabled !is Boolean || mode !is Int || mode !in 0..1 || copy !is Boolean) return null
            return ScreenshotSettingsSnapshot(revision, enabled, mode, copy, pictures)
        }
    }


}
