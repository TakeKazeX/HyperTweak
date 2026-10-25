package com.takekazex.hypertweak.settings.contract

internal interface BackGestureSettings {
    fun contextualSearchLongPress(): Boolean
    fun backGestureBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBackGestureAppearance(values: Map<String, Boolean>): Unit
}
