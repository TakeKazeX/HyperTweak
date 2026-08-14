package com.takekazex.hypertweak.hook.rules.systemui

const val GESTURE_BAR_ASSIST_REQUEST_MARKER =
    "com.takekazex.hypertweak.extra.GESTURE_BAR_ASSIST_REQUEST"

enum class GestureBarAction(val persistedId: Int) {
    DISABLED(0),
    DEFAULT_ASSISTANT(1),
    CIRCLE_TO_SEARCH(2);

    companion object {
        /**
         * Persisted ids 3 (Gemini) and 4 (ChatGPT) once mapped to bare activity launches of the
         * assistant apps. On-device those entry activities self-terminate or background themselves
         * when launched without an assist-framework session, so the options were removed; a stored
         * 3 or 4 now resolves to [DISABLED] without any migration.
         */
        fun fromPersistedId(id: Int): GestureBarAction =
            entries.firstOrNull { it.persistedId == id } ?: DISABLED
    }
}
