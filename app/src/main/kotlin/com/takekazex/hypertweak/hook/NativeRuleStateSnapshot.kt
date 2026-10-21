package com.takekazex.hypertweak.hook

internal data class NativeRuleStateSnapshot(val hidden: Boolean, val columns: Int, val contextualSearch: Boolean,
    val revision: Long = 0, val epoch: Long = 0, val assistantWidgets: Boolean = false, val recentsHaptic: Boolean = false, val folderStack: Boolean = false) {
    fun settingsValues(): Map<String, Any> = mapOf(
        Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON to hidden, Preferences.KEY_OPENED_FOLDER_COLUMNS to columns,
        Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS to contextualSearch,
        Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT to assistantWidgets,
        Preferences.KEY_RECENTS_CARD_HAPTIC to recentsHaptic,
        Preferences.KEY_FOLDER_STACK_PREVIEW to folderStack,
        Preferences.KEY_NATIVE_RULE_REVISION to revision, "prefs_epoch" to epoch)
    companion object {
        val REPLAY_DELAYS_MS = longArrayOf(0, 250, 1_000, 3_000, 10_000)

        /** A complete authoritative read, or no publication; outages are never defaults. */
        fun read(readValues: () -> Map<String, *>): NativeRuleStateSnapshot? = runCatching {
            val values = readValues()
            val hidden = values[Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON]
            val columns = values[Preferences.KEY_OPENED_FOLDER_COLUMNS]
            val contextual = values[Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS]
            val widgets = values[Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT]
            val stack = values[Preferences.KEY_FOLDER_STACK_PREVIEW]
            val haptic = values[Preferences.KEY_RECENTS_CARD_HAPTIC]
            val revision = values[Preferences.KEY_NATIVE_RULE_REVISION]
            val epoch = values["prefs_epoch"]
            // Reject the whole snapshot on any type mismatch; a partial read is never a default.
            if ((stack != null && stack !is Boolean) || (hidden != null && hidden !is Boolean) || (columns != null && columns !is Int) ||
                (haptic != null && haptic !is Boolean) || (contextual != null && contextual !is Boolean) ||
                (widgets != null && widgets !is Boolean) || (revision != null && revision !is Long) ||
                (epoch != null && epoch !is Long)) return null
            // The guards above smart-cast each value to its checked type for the rest of the block.
            val resolvedColumns = columns ?: 3
            val resolvedRevision = revision ?: 0L
            val resolvedEpoch = epoch ?: 0L
            if (resolvedRevision < 0L || resolvedEpoch < 0L || resolvedColumns !in 3..5) return null
            NativeRuleStateSnapshot(hidden ?: false, resolvedColumns, contextual ?: false,
                resolvedRevision, resolvedEpoch, widgets ?: false, haptic ?: false, stack ?: false)
        }.getOrNull()
    }
}
