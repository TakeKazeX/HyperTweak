package com.takekazex.hypertweak.hook

import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object XposedServiceManager : XposedServiceHelper.OnServiceListener {
    private val _serviceFlow = MutableStateFlow<XposedService?>(null)
    val serviceFlow = _serviceFlow.asStateFlow()

    val currentService: XposedService?
        get() = _serviceFlow.value

    fun init() {
        try {
            XposedServiceHelper.registerListener(this)
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to register XposedServiceListener", t)
        }
    }

    override fun onServiceBind(service: XposedService) {
        Log.d("HyperTweak", "XposedServiceManager: onServiceBind")
        try {
            // IMPORTANT: init Preferences BEFORE emitting to serviceFlow.
            // LaunchedEffect(serviceConnected) in MainActivity reads from Preferences immediately
            // after observing the flow update, so RemotePreferences must be ready first.
            val remotePrefs = service.getRemotePreferences(Preferences.NAME)
            Preferences.init(remotePrefs)
            Log.d("HyperTweak", "XposedServiceManager: switched Preferences to RemotePreferences (IPC-backed)")
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to init Preferences from XposedService", t)
        }
        // Emit after Preferences is ready so UI observers reload from the correct source
        _serviceFlow.value = service
    }

    override fun onServiceDied(service: XposedService) {
        Log.d("HyperTweak", "XposedServiceManager: onServiceDied")
        _serviceFlow.value = null
    }
}
