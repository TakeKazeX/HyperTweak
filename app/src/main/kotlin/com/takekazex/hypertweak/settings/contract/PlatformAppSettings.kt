package com.takekazex.hypertweak.settings.contract

internal interface PlatformAppSettings {
    fun nativePickerEnabled(key: String): Boolean
    fun freeformBlurTransition(): Boolean
    fun unlockAdaptiveRefreshPro(): Boolean
    fun unlockMoreAonGestures(): Boolean
    fun unlockMoreVisualPerception(): Boolean
}
