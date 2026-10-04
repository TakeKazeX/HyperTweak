package com.takekazex.hypertweak.hook

import android.os.Bundle
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object XposedServiceManager : XposedServiceHelper.OnServiceListener {
    private val _serviceFlow = MutableStateFlow<XposedService?>(null)
    val serviceFlow = _serviceFlow.asStateFlow()

    private val _staleTargetsFlow = MutableStateFlow<List<HookedTarget>>(emptyList())
    val staleTargetsFlow = _staleTargetsFlow.asStateFlow()

    private val _hotReloadingFlow = MutableStateFlow(false)
    val hotReloadingFlow = _hotReloadingFlow.asStateFlow()

    private val _hotReloadReportFlow = MutableStateFlow<HotReloadReport?>(null)
    val hotReloadReportFlow = _hotReloadReportFlow.asStateFlow()

    val currentService: XposedService?
        get() = _serviceFlow.value

    private var activeBatch: HotReloadBatch<HookedTarget, HotReloadTargetReport>? = null

    fun init() {
        try {
            XposedServiceHelper.registerListener(this)
            DebugLog.d("XposedService", "registered service listener")
        } catch (t: Throwable) {
            DebugLog.e("XposedService", "failed to register service listener", t)
        }
    }

    @Synchronized
    override fun onServiceBind(service: XposedService) {
        if (currentService !== service) {
            activeBatch?.cancel()
            activeBatch = null
            _hotReloadingFlow.value = false
            _hotReloadReportFlow.value = null
        }
        DebugLog.d("XposedService", "bound service api=${service.apiVersion}")
        try {
            // IMPORTANT: init Preferences BEFORE emitting to serviceFlow.
            // LaunchedEffect(serviceConnected) in MainActivity reads from Preferences immediately
            // after observing the flow update, so RemotePreferences must be ready first.
            val remotePrefs = service.getRemotePreferences(Preferences.NAME)
            Preferences.init(remotePrefs)
            DebugLog.d("XposedService", "switched Preferences to RemotePreferences")
        } catch (t: Throwable) {
            Preferences.noteRemoteBackendUnavailable()
            DebugLog.e("XposedService", "failed to init Preferences from service", t)
        }
        // Emit after Preferences is ready so UI observers reload from the correct source
        _serviceFlow.value = service
        refreshHotReloadTargets()
    }

    @Synchronized
    override fun onServiceDied(service: XposedService) {
        if (currentService !== service) return
        DebugLog.w("XposedService", "service died")
        activeBatch?.cancel()
        activeBatch = null
        Preferences.useLocalBackend()
        _serviceFlow.value = null
        _staleTargetsFlow.value = emptyList()
        _hotReloadingFlow.value = false
        _hotReloadReportFlow.value = null
    }

    fun refreshHotReloadTargets() {
        val service = currentService
        if (service == null || service.apiVersion < XposedService.API_102) {
            DebugLog.d("XposedService", "skip hot reload target query; service=${service != null} api=${service?.apiVersion}")
            _staleTargetsFlow.value = emptyList()
            return
        }

        _staleTargetsFlow.value = try {
            val stale = service.runningTargets.filter { target ->
                target.state == HookedTarget.State.STALE || target.state == HookedTarget.State.FAILED
            }
            DebugLog.d("XposedService", "stale hot reload targets=${stale.map { it.processName }}")
            stale
        } catch (t: Throwable) {
            DebugLog.e("XposedService", "failed to query hot reload targets", t)
            emptyList()
        }
    }

    @Synchronized
    fun hotReloadStaleTargets(onFinished: (HotReloadReport) -> Unit = {}) {
        if (_hotReloadingFlow.value) return
        val service = currentService
        if (service == null || service.apiVersion < XposedService.API_102) {
            DebugLog.w("XposedService", "hot reload unavailable; service=${service != null} api=${service?.apiVersion}")
            val report = HotReloadReport(
                requestedTargets = emptyList(),
                results = listOf(
                    HotReloadTargetReport(
                        processName = "libxposed",
                        succeeded = false,
                        message = "Hot reload requires libxposed service API 102"
                    )
                )
            )
            _hotReloadReportFlow.value = report
            onFinished(report)
            return
        }

        // Do not trust the UI's previous snapshot: a package update or a normal scope restart can
        // change runningTargets without rebinding the service. Query again immediately before the
        // API call so a resolved target is not reloaded a second time by mistake.
        refreshHotReloadTargets()
        val staleTargets = _staleTargetsFlow.value
        val targets = staleTargets
        if (targets.isEmpty()) {
            DebugLog.d("XposedService", "hot reload requested but no stale targets")
            val report = HotReloadReport(
                requestedTargets = emptyList(),
                results = emptyList()
            )
            _hotReloadReportFlow.value = report
            onFinished(report)
            return
        }

        DebugLog.d("XposedService", "requesting hot reload for ${targets.map { it.processName }}")
        _hotReloadingFlow.value = true
        _hotReloadReportFlow.value = null
        val requestedTargetNames = targets.map { it.processName }
        lateinit var batch: HotReloadBatch<HookedTarget, HotReloadTargetReport>
        batch = HotReloadBatch(
            targets = targets,
            request = { target, accept ->
                // PID + UID distinguish a disappeared process from another instance of the same name.
                val current = service.runningTargets.firstOrNull { it.pid == target.pid && it.uid == target.uid }
                when (current?.state) {
                    null -> accept(HotReloadTargetReport(target.processName, false, pid = target.pid,
                        outcome = HotReloadOutcome.PROCESS_EXITED))
                    HookedTarget.State.UP_TO_DATE -> accept(HotReloadTargetReport(target.processName, true, pid = target.pid))
                    HookedTarget.State.RELOADING -> accept(HotReloadTargetReport(target.processName, false, pid = target.pid,
                        outcome = HotReloadOutcome.IN_PROGRESS))
                    else -> service.hotReloadModule(current, Bundle()) { reloadedTarget, result ->
                        val status = result.status()
                        val outcome = when (status) {
                            HotReloadResult.Status.SUCCEEDED -> HotReloadOutcome.SUCCEEDED
                            HotReloadResult.Status.PROCESS_DIED -> HotReloadOutcome.PROCESS_EXITED
                            HotReloadResult.Status.IN_PROGRESS -> HotReloadOutcome.IN_PROGRESS
                            else -> HotReloadOutcome.FAILED
                        }
                        DebugLog.i("XposedService", "hot reload ${reloadedTarget.processName}/${reloadedTarget.pid}: status=$status message=${result.message()}")
                        accept(HotReloadTargetReport(reloadedTarget.processName,
                            outcome == HotReloadOutcome.SUCCEEDED, result.message(), reloadedTarget.pid, outcome))
                    }
                }
            },
            failure = { target, error ->
                DebugLog.e("XposedService", "failed to request hot reload for ${target.processName}/${target.pid}", error)
                HotReloadTargetReport(target.processName, false, error.message ?: error.javaClass.simpleName, target.pid)
            },
            finished = { results ->
                synchronized(this) {
                    if (activeBatch === batch && currentService === service) {
                        activeBatch = null
                        val report = HotReloadReport(requestedTargetNames, results)
                        _hotReloadingFlow.value = false
                        _hotReloadReportFlow.value = report
                        refreshHotReloadTargets()
                        onFinished(report)
                    }
                }
            }
        )
        activeBatch = batch
        batch.start()
    }
}
