package com.takekazex.hypertweak.hook

/** Complete settings committed by the app; the existing authenticated settings channel carries it. */
internal data class BackGestureSettingsSnapshot(val values: List<Boolean>, val revision: Long, val epoch: Long) {
    init { require(values.size == KEYS.size && revision >= 0 && epoch >= 0) }
    fun value(key: String): Boolean? = KEYS.indexOf(key).takeIf { it >= 0 }?.let(values::get)
    fun mutate(key: String, value: Boolean, nextRevision: Long): BackGestureSettingsSnapshot {
        val index = KEYS.indexOf(key)
        require(index >= 0 && nextRevision > revision)
        return copy(values = values.toMutableList().also { it[index] = value }, revision = nextRevision)
    }
    fun save(): Array<Any> = arrayOf(revision, epoch, *values.toTypedArray())
    companion object {
        const val REVISION = "back_gesture_settings_revision"
        val KEYS = listOf(Preferences.KEY_BACK_GESTURE_ENABLED,
            Preferences.KEY_BACK_GESTURE_HYPEROS_INDICATOR, Preferences.KEY_BACK_GESTURE_HYPEROS_HAPTICS,
            Preferences.KEY_BACK_GESTURE_ENHANCED_HAPTICS, Preferences.KEY_BACK_GESTURE_SLIDE_ANIMATION,
            Preferences.KEY_BACK_GESTURE_CROSS_ACTIVITY_WALLPAPER, Preferences.KEY_BACK_GESTURE_CROSS_TASK_WALLPAPER,
            Preferences.KEY_CONTEXTUAL_SEARCH_HAPTICS, Preferences.KEY_FULLSCREEN_DIRECT_BACK,
            Preferences.KEY_BACK_GESTURE_INDICATOR_BLUR, Preferences.KEY_BACK_GESTURE_HAPTICS_DISABLED)
        fun read(values: Map<String, *>): BackGestureSettingsSnapshot? = runCatching {
            val flags = KEYS.map { key ->
                val value = values[key]
                require(value == null || value is Boolean)
                value ?: false
            }
            val revision = values[REVISION]
            val epoch = values["prefs_epoch"]
            require(revision == null || revision is Long)
            require(epoch == null || epoch is Long)
            BackGestureSettingsSnapshot(flags, revision ?: 0L, epoch ?: 0L)
        }.getOrNull()
        fun restore(state: Any?): BackGestureSettingsSnapshot? {
            val saved = state as? Array<*> ?: return null
            // Older records have seven, eight or nine flags. Preserve all stored values and
            // their identity; appended opt-in settings default to false.
            val array = if (saved.size in 9 until KEYS.size + 2) {
                saved.toList().plus(List(KEYS.size + 2 - saved.size) { false }).toTypedArray()
            } else saved
            if (array.size != KEYS.size + 2 || array[0] !is Long || array[1] !is Long ||
                array.drop(2).any { it !is Boolean }) return null
            return read(KEYS.zip(array.drop(2)).toMap() + mapOf(REVISION to array[0], "prefs_epoch" to array[1]))
        }
    }
}

internal class BackGestureSettingsLedger {
    private var current: BackGestureSettingsSnapshot? = null
    @Synchronized fun latest() = current
    @Synchronized fun offer(next: BackGestureSettingsSnapshot): Boolean {
        val old = current
        if (old != null && (next.epoch < old.epoch ||
                next.epoch == old.epoch && next.revision < old.revision ||
                next.epoch == old.epoch && next.revision == old.revision && next != old)) return false
        current = next
        return true
    }
}
