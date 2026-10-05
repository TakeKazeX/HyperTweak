package com.takekazex.hypertweak.hook

internal data class NativeRuleStateSnapshot(val hidden: Boolean, val columns: Int, val contextualSearch: Boolean,
    val revision: Long = 0, val epoch: Long = 0, val assistantWidgets: Boolean = false) {
    companion object {
        val REPLAY_DELAYS_MS = longArrayOf(0, 250, 1_000, 3_000, 10_000)

        /** A complete authoritative read, or no publication; outages are never defaults. */
        fun read(readValues: () -> Map<String, *>): NativeRuleStateSnapshot? = runCatching {
            val values = readValues()
            val hidden = values[Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON]
            val columns = values[Preferences.KEY_OPENED_FOLDER_COLUMNS]
            val contextual = values[Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS]
            val widgets = values[Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT]
            val revision = values[Preferences.KEY_NATIVE_RULE_REVISION]
            val epoch = values["prefs_epoch"]
            if ((hidden != null && hidden !is Boolean) || (columns != null && columns !is Int) ||
                (contextual != null && contextual !is Boolean) || (widgets != null && widgets !is Boolean) || (revision != null && revision !is Long) ||
                (epoch != null && epoch !is Long) || (revision as? Long ?: 0) < 0 || (epoch as? Long ?: 0) < 0 ||
                (columns as? Int ?: 3) !in 3..5) return null
            NativeRuleStateSnapshot(hidden as? Boolean ?: false,
                columns as? Int ?: 3, contextual as? Boolean ?: false, revision as? Long ?: 0, epoch as? Long ?: 0, widgets as? Boolean ?: false)
        }.getOrNull()
    }
}
