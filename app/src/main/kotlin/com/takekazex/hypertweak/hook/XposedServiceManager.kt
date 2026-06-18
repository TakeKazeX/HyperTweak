package com.takekazex.hypertweak.hook

import android.os.Bundle
import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object XposedServiceManager : XposedServiceHelper.OnServiceListener {
    private val _serviceFlow = MutableStateFlow<XposedService?>(null)
    val serviceFlow = _serviceFlow.asStateFlow()

    private val _staleTargetsFlow = MutableStateFlow<List<HookedTarget>>(emptyList())
    val staleTargetsFlow = _staleTargetsFlow.asStateFlow()

    private val _hotReloadingFlow = MutableStateFlow(false)
    val hotReloadingFlow = _hotReloadingFlow.asStateFlow()

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
        refreshHotReloadTargets()
    }

    override fun onServiceDied(service: XposedService) {
        Log.d("HyperTweak", "XposedServiceManager: onServiceDied")
        _serviceFlow.value = null
        _staleTargetsFlow.value = emptyList()
        _hotReloadingFlow.value = false
    }

    fun refreshHotReloadTargets() {
        val service = currentService
        if (service == null || service.apiVersion < XposedService.API_102) {
            _staleTargetsFlow.value = emptyList()
            return
        }

        _staleTargetsFlow.value = try {
            service.runningTargets.filter { target ->
                target.state == HookedTarget.State.STALE
            }
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to query hot reload targets", t)
            emptyList()
        }
    }

    fun hotReloadStaleTargets(onFinished: (Boolean) -> Unit = {}) {
        val service = currentService
        if (service == null || service.apiVersion < XposedService.API_102) {
            onFinished(false)
            return
        }

        val targets = _staleTargetsFlow.value.ifEmpty {
            refreshHotReloadTargets()
            _staleTargetsFlow.value
        }
        if (targets.isEmpty()) {
            onFinished(false)
            return
        }

        _hotReloadingFlow.value = true
        val remaining = AtomicInteger(targets.size)
        val hasFailure = AtomicBoolean(false)

        targets.forEach { target ->
            try {
                service.hotReloadModule(target, Bundle()) { reloadedTarget, result ->
                    val success = result.status() == HotReloadResult.Status.SUCCEEDED
                    if (!success) {
                        hasFailure.set(true)
                        Log.e(
                            "HyperTweak",
                            "Hot reload failed for ${reloadedTarget.processName}: ${result.message()}"
                        )
                    } else {
                        Log.d("HyperTweak", "Hot reload succeeded for ${reloadedTarget.processName}")
                    }

                    if (remaining.decrementAndGet() == 0) {
                        _hotReloadingFlow.value = false
                        refreshHotReloadTargets()
                        onFinished(!hasFailure.get())
                    }
                }
            } catch (t: Throwable) {
                hasFailure.set(true)
                Log.e("HyperTweak", "Failed to request hot reload for ${target.processName}", t)
                if (remaining.decrementAndGet() == 0) {
                    _hotReloadingFlow.value = false
                    refreshHotReloadTargets()
                    onFinished(false)
                }
            }
        }
    }
}
