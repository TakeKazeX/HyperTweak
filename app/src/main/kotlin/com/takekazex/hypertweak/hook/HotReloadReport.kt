package com.takekazex.hypertweak.hook

enum class HotReloadOutcome { SUCCEEDED, FAILED, PROCESS_EXITED, IN_PROGRESS }

data class HotReloadTargetReport(
    val processName: String,
    val succeeded: Boolean,
    val message: String? = null,
    val pid: Int? = null,
    val outcome: HotReloadOutcome = if (succeeded) HotReloadOutcome.SUCCEEDED else HotReloadOutcome.FAILED
)

data class HotReloadReport(
    val requestedTargets: List<String>,
    val results: List<HotReloadTargetReport>
) {
    val succeededCount: Int
        get() = results.count { it.succeeded }

    val failedCount: Int
        get() = results.count { it.outcome == HotReloadOutcome.FAILED }
    val exitedCount: Int get() = results.count { it.outcome == HotReloadOutcome.PROCESS_EXITED }
    val pendingCount: Int get() = results.count { it.outcome == HotReloadOutcome.IN_PROGRESS }
}
