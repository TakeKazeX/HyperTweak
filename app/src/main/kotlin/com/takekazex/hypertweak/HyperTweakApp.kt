package com.takekazex.hypertweak

import android.app.Application
import android.content.Context
import com.takekazex.hypertweak.hook.NativeRuleConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.util.DebugLog

class HyperTweakApp : Application() {
    override fun onCreate() {
        super.onCreate()

        DebugLog.setProcessTag("app")
        // Init connection to LSPosed preferences as early as possible
        XposedServiceManager.init()

        // Synchronously initialize local preferences
        val localPrefs = getSharedPreferences(Preferences.NAME, Context.MODE_PRIVATE)
        Preferences.init(localPrefs, useLocalOnly = true)
        Preferences.initLocalCache(this)

        // Republish the native rule flags on every start so the launcher-side payload sees the
        // current state even when the last write came from an older build.
        NativeRuleConfig.publish(this, Preferences.hideRecentsClearButton())
    }
}
