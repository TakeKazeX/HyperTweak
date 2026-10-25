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
        fun owns(key: String) = key == ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME ||
            key == ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE || key == ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD ||
            key == ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY
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

    fun mutate(key: String, value: Any, revision: Long): ScreenshotSettingsSnapshot? {
        if (!owns(key) || revision <= this.revision) return null
        return read(revision, mapOf(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME to enabled,
            ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE to editorMode,
            ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD to copyToClipboard,
            ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY to picturesDirectory) + (key to value))
    }
}

internal class ScreenshotSettingsLedger {
    @Volatile private var snapshot: ScreenshotSettingsSnapshot? = null
    fun latest() = snapshot
    @Synchronized fun accept(next: ScreenshotSettingsSnapshot): Boolean {
        if (next.revision <= (snapshot?.revision ?: 0)) return false
        snapshot = next
        return true
    }
    fun save(): Array<Any>? = snapshot?.let { arrayOf(it.revision, it.enabled, it.editorMode, it.copyToClipboard, it.picturesDirectory) }
    fun restore(saved: Any?) {
        val values = saved as? Array<*> ?: return
        // Older payloads restore missing save options as off.
        if (values.size !in 3..5) return
        val revision = values[0] as? Long ?: return
        ScreenshotSettingsSnapshot.read(revision, mapOf(
            ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME to values[1],
            ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE to values[2],
            ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD to (values.getOrNull(3) ?: false),
            ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY to (values.getOrNull(4) ?: false)))?.let(::accept)
    }
}
