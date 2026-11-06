package com.takekazex.hypertweak

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.app.Application
import android.content.Context
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.hook.bootstrap.ProcessLogging
import com.takekazex.hypertweak.util.logging.DebugLog

class HyperTweakApp : Application() {
    override fun onCreate() {
        super.onCreate()

        ProcessLogging.bind(app = true)
        DebugLog.setProcessTag("app")
        com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSignal.attach(this)
        com.takekazex.hypertweak.hook.nativebridge.NativeUpgradeManager.restore(this)
        // Init connection to LSPosed preferences as early as possible
        XposedServiceManager.init()

        // Synchronously initialize local preferences
        val localPrefs = getSharedPreferences(AppSettingsKeys.NAME, Context.MODE_PRIVATE)
        Preferences.init(localPrefs, useLocalOnly = true)
        Preferences.initLocalCache(this)
        com.takekazex.hypertweak.app.PreferencesAppSettings.bind(this)
        DebugLog.ensureSession()

        // Republish the native rule flags on every start so the launcher-side payload sees the
        // current state even when the last write came from an older build.
        NativeRuleConfig.publish(this, Preferences.hideRecentsClearButton())

        // The boot receiver normally re-applies this after OTA; opening the app is a second
        // recovery point if the OEM blocked the boot broadcast.
        val systemHelperManaged = runCatching {
            Preferences.getBoolean(PlatformSettingsKeys.KEY_SYSTEM_HELPER_MANAGED, false)
        }.getOrDefault(false)
        if (systemHelperManaged) {
            Thread({
                com.takekazex.hypertweak.app.DefaultAppServices.systemHelper.reconcileBlocking(this)
            }, "HyperTweak-SystemHelper").start()
        }
    }
}
