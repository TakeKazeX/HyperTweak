package com.takekazex.hypertweak.settings.contract

/** Independent photo/file picker keys inherit the legacy combined switch; explicit false wins. */
object NativePickerSettings {
    fun enabled(scoped: Boolean?, legacy: Boolean): Boolean = scoped ?: legacy
}
