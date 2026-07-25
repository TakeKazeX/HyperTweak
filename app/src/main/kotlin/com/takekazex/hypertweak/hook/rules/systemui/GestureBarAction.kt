package com.takekazex.hypertweak.hook.rules.systemui

const val GESTURE_BAR_ASSIST_REQUEST_MARKER =
    "com.takekazex.hypertweak.extra.GESTURE_BAR_ASSIST_REQUEST"

enum class GestureBarAction(val persistedId: Int) {
    DISABLED(0),
    DEFAULT_ASSISTANT(1),
    CIRCLE_TO_SEARCH(2),
    GEMINI(3),
    CHATGPT(4);

    companion object {
        fun fromPersistedId(id: Int): GestureBarAction =
            entries.firstOrNull { it.persistedId == id } ?: DISABLED
    }
}
