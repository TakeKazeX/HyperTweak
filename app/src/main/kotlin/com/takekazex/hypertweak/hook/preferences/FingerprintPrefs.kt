package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

/** Per-scene fingerprint-icon hiding, with fallback to the legacy single switch. */
internal object FingerprintPrefs {
    /**
     * Reads one scoped fingerprint-hiding switch while preserving the original aggregate switch
     * for upgrades. An explicit scoped false must beat a legacy true, hence the contains() check
     * rather than a simple OR.
     */
    private fun scope(key: String): Boolean {
        return if (Preferences.contains(key)) {
            Preferences.getBoolean(key, false)
        } else {
            Preferences.getBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT, false)
        }
    }

    /** Reads the independent always-on-display fingerprint switch. */
    fun hideFingerprintAodEnabled(): Boolean = scope(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD)

    /** Reads the independent interactive-lockscreen fingerprint switch. */
    fun hideFingerprintLockscreenEnabled(): Boolean = scope(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN)

    fun hideFingerprintAppAuthEnabled(): Boolean = scope(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH)
}
