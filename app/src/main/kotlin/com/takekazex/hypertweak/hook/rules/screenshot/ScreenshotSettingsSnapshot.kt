package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.hook.Preferences

/** Complete, acknowledged screenshot settings; revisions survive app restarts within a boot. */
internal data class ScreenshotSettingsSnapshot(val revision: Long, val enabled: Boolean, val editorMode: Int) {
    companion object {
        const val REVISION = "screenshot_settings_revision"
        fun owns(key: String) = key == Preferences.KEY_SCREENSHOT_AUTO_FRAME || key == Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE
        fun read(revision: Long, values: Map<String, *>): ScreenshotSettingsSnapshot? {
            if (revision <= 0) return null
            val enabled = values[Preferences.KEY_SCREENSHOT_AUTO_FRAME] ?: false
            val mode = values[Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE] ?: 0
            if (enabled !is Boolean || mode !is Int || mode !in 0..1) return null
            return ScreenshotSettingsSnapshot(revision, enabled, mode)
        }
    }

    fun mutate(key: String, value: Any, revision: Long): ScreenshotSettingsSnapshot? {
        if (!owns(key) || revision <= this.revision) return null
        return read(revision, mapOf(Preferences.KEY_SCREENSHOT_AUTO_FRAME to enabled,
            Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE to editorMode) + (key to value))
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
    fun save(): Array<Any>? = snapshot?.let { arrayOf(it.revision, it.enabled, it.editorMode) }
    fun restore(saved: Any?) {
        val values = saved as? Array<*> ?: return
        if (values.size != 3) return
        val revision = values[0] as? Long ?: return
        ScreenshotSettingsSnapshot.read(revision, mapOf(
            Preferences.KEY_SCREENSHOT_AUTO_FRAME to values[1],
            Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE to values[2]))?.let(::accept)
    }
}

/** An existing editor keeps its source/layer representation until it is released. */
internal class ScreenshotEditorSessions(private val currentMode: () -> Int) {
    private val modes = java.util.WeakHashMap<Any, Int>()
    @Synchronized fun mode(owner: Any): Int = modes.getOrPut(owner, currentMode)
    @Synchronized fun release(owner: Any) { modes.remove(owner) }
}
