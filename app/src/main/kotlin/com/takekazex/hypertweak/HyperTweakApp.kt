package com.takekazex.hypertweak

import android.app.Application
import android.content.Context
import android.os.Build
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager

class HyperTweakApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Init connection to LSPosed preferences as early as possible
        XposedServiceManager.init()

        // Synchronously initialize local preferences from device protected storage context to match Xposed remote prefs
        val deContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        val localPrefs = deContext.getSharedPreferences(Preferences.NAME, Context.MODE_PRIVATE)
        Preferences.init(localPrefs, useLocalOnly = true)
    }
}
