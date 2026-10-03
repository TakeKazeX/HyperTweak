package com.takekazex.hypertweak.hook

internal data class NativeRuleStateSnapshot(val hidden: Boolean, val columns: Int, val contextualSearch: Boolean) {
    companion object {
        val REPLAY_DELAYS_MS = longArrayOf(0, 250, 1_000, 3_000, 10_000)

        /** A complete authoritative read, or no publication; outages are never defaults. */
        fun read(readValues: () -> Map<String, *>): NativeRuleStateSnapshot? = runCatching {
            val values = readValues()
            val hidden = values[Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON]
            val columns = values[Preferences.KEY_OPENED_FOLDER_COLUMNS]
            val contextual = values[Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS]
            if ((hidden != null && hidden !is Boolean) || (columns != null && columns !is Int) ||
                (contextual != null && contextual !is Boolean)) return null
            NativeRuleStateSnapshot(hidden as? Boolean ?: false,
                (columns as? Int ?: 3).coerceIn(3, 5), contextual as? Boolean ?: false)
        }.getOrNull()
    }
}
