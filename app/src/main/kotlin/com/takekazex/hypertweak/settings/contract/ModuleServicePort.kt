package com.takekazex.hypertweak.settings.contract

import kotlinx.coroutines.flow.StateFlow

/** Immutable binding identity: a rebind with the same framework metadata still refreshes readers. */
data class ModuleServiceInfo(val frameworkName: String, val frameworkVersion: String,
    val apiVersion: Int, val bindingId: Long)

internal interface ModuleServicePort {
    val connection: StateFlow<ModuleServiceInfo?>
    val staleProcessNames: StateFlow<List<String>>
    val hotReloadingFlow: StateFlow<Boolean>
    val hotReloadReportFlow: StateFlow<HotReloadReport?>
    fun refreshHotReloadTargets()
    fun hotReloadStaleTargets(onFinished: (HotReloadReport) -> Unit = {})
}
