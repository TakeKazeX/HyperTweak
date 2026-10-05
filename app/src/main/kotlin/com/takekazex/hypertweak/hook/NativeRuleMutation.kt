package com.takekazex.hypertweak.hook

/** A failed editor can leave a shadow map changed. Build the next transaction from the last ACK. */
internal object NativeRuleMutation {
    fun canMirror(snapshot: NativeRuleStateSnapshot, localEpoch: Long, localRevision: Long): Boolean =
        localEpoch == snapshot.epoch && localRevision == snapshot.revision

    fun apply(base: NativeRuleStateSnapshot, key: String, value: Any, revision: Long): NativeRuleStateSnapshot {
        val next = when (key) {
            Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON -> base.copy(hidden = value as Boolean, revision = revision)
            Preferences.KEY_OPENED_FOLDER_COLUMNS -> base.copy(columns = value as Int, revision = revision)
            Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS -> base.copy(contextualSearch = value as Boolean, revision = revision)
            else -> error("unsupported native setting")
        }
        require(next.columns in 3..5 && next.revision >= 0)
        return next
    }
}
