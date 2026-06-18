package com.takekazex.hypertweak.hook

import android.os.Bundle
import com.takekazex.hypertweak.util.DebugLog
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
            DebugLog.d("XposedService", "registered service listener")
        } catch (t: Throwable) {
            DebugLog.e("XposedService", "failed to register service listener", t)
        }
    }

    override fun onServiceBind(service: XposedService) {
        DebugLog.d("XposedService", "bound service api=${service.apiVersion}")
        try {
            // IMPORTANT: init Preferences BEFORE emitting to serviceFlow.
            // LaunchedEffect(serviceConnected) in MainActivity reads from Preferences immediately
            // after observing the flow update, so RemotePreferences must be ready first.
            val remotePrefs = service.getRemotePreferences(Preferences.NAME)
            Preferences.init(remotePrefs)
            DebugLog.d("XposedService", "switched Preferences to RemotePreferences")
        } catch (t: Throwable) {
            DebugLog.e("XposedService", "failed to init Preferences from service", t)
        }
        // Emit after Preferences is ready so UI observers reload from the correct source
        _serviceFlow.value = service
        refreshHotReloadTargets()
    }

    override fun onServiceDied(service: XposedService) {
        DebugLog.w("XposedService", "service died")
        _serviceFlow.value = null
        _staleTargetsFlow.value = emptyList()
        _hotReloadingFlow.value = false
    }

    fun refreshHotReloadTargets() {
        val service = currentService
        if (service == null || service.apiVersion < XposedService.API_102) {
            DebugLog.w("XposedService", "skip hot reload target query; service=${service != null} api=${service?.apiVersion}")
            _staleTargetsFlow.value = emptyList()
            return
        }

        _staleTargetsFlow.value = try {
            val stale = service.runningTargets.filter { target ->
                target.state == HookedTarget.State.STALE
            }
            DebugLog.d("XposedService", "stale hot reload targets=${stale.map { it.processName }}")
            stale
        } catch (t: Throwable) {
            DebugLog.e("XposedService", "failed to query hot reload targets", t)
            emptyList()
        }
    }

    fun hotReloadStaleTargets(onFinished: (Boolean) -> Unit = {}) {
        val service = currentService
        if (service == null || service.apiVersion < XposedService.API_102) {
            DebugLog.w("XposedService", "hot reload unavailable; service=${service != null} api=${service?.apiVersion}")
            onFinished(false)
            return
        }

        val staleTargets = _staleTargetsFlow.value.ifEmpty {
            refreshHotReloadTargets()
            _staleTargetsFlow.value
        }
        val targets = staleTargets
        if (targets.isEmpty()) {
            DebugLog.w("XposedService", "hot reload requested but no stale targets")
            onFinished(false)
            return
        }

        DebugLog.d("XposedService", "requesting hot reload for ${targets.map { it.processName }}")
        _hotReloadingFlow.value = true
        val remaining = AtomicInteger(targets.size)
        val hasFailure = AtomicBoolean(false)

        targets.forEach { target ->
            try {
                service.hotReloadModule(target, Bundle()) { reloadedTarget, result ->
                    val success = result.status() == HotReloadResult.Status.SUCCEEDED
                    if (!success) {
                        hasFailure.set(true)
                        DebugLog.e("XposedService", "hot reload failed for ${reloadedTarget.processName}: ${result.message()}")
                    } else {
                        DebugLog.d("XposedService", "hot reload succeeded for ${reloadedTarget.processName}")
                    }

                    if (remaining.decrementAndGet() == 0) {
                        _hotReloadingFlow.value = false
                        refreshHotReloadTargets()
                        onFinished(!hasFailure.get())
                    }
                }
            } catch (t: Throwable) {
                hasFailure.set(true)
                DebugLog.e("XposedService", "failed to request hot reload for ${target.processName}", t)
                if (remaining.decrementAndGet() == 0) {
                    _hotReloadingFlow.value = false
                    refreshHotReloadTargets()
                    onFinished(false)
                }
            }
        }
    }
}
