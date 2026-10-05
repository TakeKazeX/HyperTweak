package com.takekazex.hypertweak.hook

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
        arrayOf(it.hidden, it.columns, it.contextualSearch, it.revision, it.epoch)
    }
    fun restore(state: Any?) {
        val values = state as? Array<*> ?: return
        if (values.size != 5 || values.any { it == null }) return
        NativeRuleStateSnapshot.read { mapOf(Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON to values[0],
            Preferences.KEY_OPENED_FOLDER_COLUMNS to values[1], Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS to values[2],
            Preferences.KEY_NATIVE_RULE_REVISION to values[3], "prefs_epoch" to values[4]) }?.let(::offer)
    }
}
