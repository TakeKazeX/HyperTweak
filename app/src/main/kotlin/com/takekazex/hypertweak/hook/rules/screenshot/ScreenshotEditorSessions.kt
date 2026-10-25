package com.takekazex.hypertweak.hook.rules.screenshot

/** An existing editor keeps its source/layer representation until it is released. */
internal class ScreenshotEditorSessions(private val currentMode: () -> Int) {
    private val modes = java.util.WeakHashMap<Any, Int>()
    @Synchronized fun mode(owner: Any): Int = modes.getOrPut(owner, currentMode)
    @Synchronized fun release(owner: Any) { modes.remove(owner) }
}
