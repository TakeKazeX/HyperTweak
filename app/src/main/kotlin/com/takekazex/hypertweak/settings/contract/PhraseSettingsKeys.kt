package com.takekazex.hypertweak.settings.contract

object PhraseSettingsKeys {
    const val PACKAGE = "com.miui.phrase"
    const val KEY_ALLOW_IME_HISTORY = "phrase_allow_ime_history"
    const val KEY_UNLOCK_LIMITS = "phrase_unlock_limits"
    const val KEY_CLIPBOARD_LIMITS = "phrase_clipboard_limits"
    const val KEY_CLIPBOARD_COUNT = "phrase_clipboard_count"
    const val KEY_CLIPBOARD_DAYS = "phrase_clipboard_days"
    const val DEFAULT_COUNT = 20
    const val DEFAULT_DAYS = 3

    val clipboardCounts = listOf(10, 20, 50, 100, 250, 500)
    val clipboardDays = listOf(3, 7, 30, 90)

    fun normalizeCount(value: Int): Int = value.takeIf { it in clipboardCounts } ?: DEFAULT_COUNT
    fun normalizeDays(value: Int): Int = value.takeIf { it in clipboardDays } ?: DEFAULT_DAYS
    fun retentionMillis(days: Int): Long = normalizeDays(days) * 86_400_000L
}
