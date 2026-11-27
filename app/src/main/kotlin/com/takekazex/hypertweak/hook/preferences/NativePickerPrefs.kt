package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.NativePickerSettings
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/**
 * Per-picker native picker switches, falling back to the shared one when a picker has none. An
 * explicit false must beat the legacy combined switch, including runtime snapshot reset.
 */
internal object NativePickerPrefs {
    fun nativePickerEnabled(key: String): Boolean = NativePickerSettings.enabled(
        if (Preferences.contains(key)) Preferences.getBoolean(key, false) else null,
        Preferences.getBoolean(PlatformSettingsKeys.KEY_NATIVE_PICKER, false)
    )
}
