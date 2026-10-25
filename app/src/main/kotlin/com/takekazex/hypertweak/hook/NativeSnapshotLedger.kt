package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/** Commit order, independent of transport replay revisions and publisher/classloader lifetime. */
internal class NativeSnapshotLedger {
    companion object {
        fun nextRevision(previous: Long, clock: Long): Long {
            require(previous in 0 until Long.MAX_VALUE && clock >= 0)
            return maxOf(previous + 1, clock)
        }
    }
    private var current: NativeRuleStateSnapshot? = null
    @Synchronized fun offer(next: NativeRuleStateSnapshot): Boolean {
        val old = current
        if (old != null) {
            if (next.epoch < old.epoch || (next.epoch == old.epoch && next.revision < old.revision)) return false
            if (next.epoch == old.epoch && next.revision == old.revision && next != old) return false
        }
        current = next
        return true
    }
    @Synchronized fun latest(): NativeRuleStateSnapshot? = current
    @Synchronized fun save(): Array<Any>? = current?.let {
        arrayOf(it.hidden, it.columns, it.contextualSearch, it.revision, it.epoch, it.assistantWidgets, it.recentsHaptic, it.folderStack, it.widgetStack, it.xiaoaiOptions)
    }
    fun restore(state: Any?) {
        val values = state as? Array<*> ?: return
        if (values.size !in 6..10 || values.any { it == null }) return
        NativeRuleStateSnapshot.read { mapOf(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON to values[0],
            PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS to values[1], PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS to values[2],
            PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to values[3], "prefs_epoch" to values[4], PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT to values[5],
            PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to (values.getOrNull(6) ?: false),
            PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW to (values.getOrNull(7) ?: false),
            PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK to (values.getOrNull(8) ?: false),
            PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS to (values.getOrNull(9) ?: false)) }?.let(::offer)
    }
}
