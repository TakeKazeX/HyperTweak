package com.takekazex.hypertweak.settings.contract

internal interface FingerprintSettings {
    fun hideFingerprintAodEnabled(): Boolean
    fun hideFingerprintLockscreenEnabled(): Boolean
    fun hideFingerprintAppAuthEnabled(): Boolean
}
