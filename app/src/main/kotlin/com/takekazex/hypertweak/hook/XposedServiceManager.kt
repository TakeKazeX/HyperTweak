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
        _serviceFlow.value = service
    }

    override fun onServiceDied(service: XposedService) {
        Log.d("HyperTweak", "XposedServiceManager: onServiceDied")
        _serviceFlow.value = null
    }
}
