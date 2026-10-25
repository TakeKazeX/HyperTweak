package com.takekazex.hypertweak.settings.contract

/** Vocabulary shared by IME runtime configuration and its settings page. */
object ImeLayout {
    const val BUTTON_HIDE_IME = "back"
    const val BUTTON_HOME_HANDLE = "home_handle"
    const val BUTTON_IME_SWITCHER = "ime_switcher"
    const val BUTTON_NONE = "space"
    const val RAISE_STYLE_AOSP = 0
    const val RAISE_STYLE_MIUI = 1
    fun button(value: String, default: String): String = value.trim().takeIf { it.isNotEmpty() } ?: default
}
