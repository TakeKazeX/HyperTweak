package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSignal
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/** Wi-Fi saved-networks entry position. */
internal object SavedNetworksPrefs {
    /** Recover a Wi-Fi position saved while the module was using its local settings backend. */
    fun recoverUnpublishedPosition() {
        val key = PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORKS_ENTRY_POSITION
        val acknowledged = RuntimeSettingsSignal.lastAcknowledged()?.values ?: return
        if (acknowledged.containsKey(key)) return
        val local = Preferences.localSource() ?: return
        val position = local.all[key] as? Int ?: return
        if (position in PlatformSettingsKeys.WIFI_SAVED_NETWORKS_POSITION_BELOW_ACCELERATION..
            PlatformSettingsKeys.WIFI_SAVED_NETWORKS_POSITION_ABOVE_ADVANCED) {
            Preferences.putInt(key, position)
        }
    }
}
