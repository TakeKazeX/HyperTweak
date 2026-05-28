package com.takekazex.hypertweak

import android.app.Application
import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager

class HyperTweakApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Init connection to LSPosed preferences as early as possible
        XposedServiceManager.init()

        // Synchronously initialize local preferences to prevent theme flash on cold start
        val localPrefs = getSharedPreferences(Preferences.NAME, Context.MODE_PRIVATE)
        Preferences.init(localPrefs, useLocalOnly = true)
    }
}
