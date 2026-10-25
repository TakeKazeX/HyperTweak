package com.takekazex.hypertweak.app

import android.content.SharedPreferences
import androidx.core.content.edit
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import kotlinx.coroutines.delay

/** App-owned service/reinstall reconciliation; Compose only observes the returned activation. */
internal class AppActivationCoordinator(
    private val local: SharedPreferences,
    private val moduleActive: () -> Boolean,
    private val reload: () -> Unit,
    private val settings: Settings = object : Settings {
        override val initialized get() = Preferences.isInitialized
        override fun hasRemoteConfig() = Preferences.hasRemoteConfig()
        override fun clear() = Preferences.clearAllSettings()
    },
    private val serviceAvailable: () -> Boolean = { XposedServiceManager.currentService != null },
    private val awaitService: suspend () -> Unit = { delay(500) },
    private val refreshTargets: () -> Unit = XposedServiceManager::refreshHotReloadTargets
) {
    interface Settings {
        val initialized: Boolean
        fun hasRemoteConfig(): Boolean
        fun clear(): Boolean
    }
    private companion object {
        const val FIRST_RUN = "first_run_token"
        const val LAST_ACTIVE = "last_known_module_activated"
    }
    val initialActive get() = moduleActive() || local.getBoolean(LAST_ACTIVE, false)

    /** Null leaves the current UI value alone while a newer service binding takes over. */
    suspend fun refresh(connected: Boolean): Boolean? {
        if (moduleActive() || connected) {
            // Check before writing LAST_ACTIVE: its absence is the evidence of a fresh data dir.
            // A rejected reset must keep that evidence for the next bind or app launch.
            if (ensureFreshInstallReset()) local.edit { putBoolean(LAST_ACTIVE, true) }
            reload()
            refreshTargets()
            return true
        }
        awaitService()
        if (serviceAvailable()) return null
        if (local.contains(LAST_ACTIVE)) local.edit { putBoolean(LAST_ACTIVE, false) }
        return false
    }
    private fun ensureFreshInstallReset(): Boolean {
        if (local.contains(FIRST_RUN)) return true
        if (!settings.initialized) return false
        if (!local.contains(LAST_ACTIVE) && settings.hasRemoteConfig() && !settings.clear()) return false
        local.edit { putBoolean(FIRST_RUN, true) }
        return true
    }
}
