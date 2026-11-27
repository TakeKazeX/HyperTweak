package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.settings.contract.BackGestureKeys


/** Complete settings committed by the app; the existing authenticated settings channel carries it. */
internal data class BackGestureSettingsSnapshot(val values: List<Boolean>, val revision: Long, val epoch: Long) {
    init { require(values.size == KEYS.size && revision >= 0 && epoch >= 0) }
    fun value(key: String): Boolean? = KEYS.indexOf(key).takeIf { it >= 0 }?.let(values::get)
    companion object {
        const val REVISION = "back_gesture_settings_revision"
        val KEYS = listOf(BackGestureKeys.KEY_BACK_GESTURE_ENABLED,
            BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_INDICATOR, BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_HAPTICS,
            BackGestureKeys.KEY_BACK_GESTURE_ENHANCED_HAPTICS, BackGestureKeys.KEY_BACK_GESTURE_SLIDE_ANIMATION,
            BackGestureKeys.KEY_BACK_GESTURE_CROSS_ACTIVITY_WALLPAPER, BackGestureKeys.KEY_BACK_GESTURE_CROSS_TASK_WALLPAPER,
            PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS, BackGestureKeys.KEY_FULLSCREEN_DIRECT_BACK,
            BackGestureKeys.KEY_BACK_GESTURE_INDICATOR_BLUR, BackGestureKeys.KEY_BACK_GESTURE_HAPTICS_DISABLED,
            BackGestureKeys.KEY_BACK_GESTURE_UPSTREAM_DETECTION_DISABLED,
            BackGestureKeys.KEY_STATUS_BAR_FOLLOW, BackGestureKeys.KEY_RETURN_HOME_ICON_PREVIEW)
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
    }
}
