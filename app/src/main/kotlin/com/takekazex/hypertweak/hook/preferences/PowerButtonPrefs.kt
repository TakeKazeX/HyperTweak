package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/** Long-press power action, with migration of the legacy Circle to Search boolean. */
internal object PowerButtonPrefs {
    /**
     * The current long-press power action ([PlatformSettingsKeys.POWER_BUTTON_ACTION_DISABLED] and
     * siblings). Reads [PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION]; when it is unset it migrates
     * the legacy [PlatformSettingsKeys.KEY_POWER_BUTTON_CTS] boolean (on → Circle to Search), so
     * existing users keep their binding without touching the setting.
     */
    fun powerButtonAction(): Int {
        val action = Preferences.getInt(PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION, -1)
        if (action == PlatformSettingsKeys.POWER_BUTTON_ACTION_DISABLED ||
            action == PlatformSettingsKeys.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH ||
            action == PlatformSettingsKeys.POWER_BUTTON_ACTION_DEFAULT_ASSISTANT ||
            action == PlatformSettingsKeys.POWER_BUTTON_ACTION_GOOGLE_LENS
        ) {
            return action
        }
        return if (Preferences.getBoolean(PlatformSettingsKeys.KEY_POWER_BUTTON_CTS, false)) {
            PlatformSettingsKeys.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH
        } else {
            PlatformSettingsKeys.POWER_BUTTON_ACTION_DISABLED
        }
    }
}
