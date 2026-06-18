package com.takekazex.hypertweak.hook

data class HotReloadTargetReport(
    val processName: String,
    val succeeded: Boolean,
    val message: String? = null
)

data class HotReloadReport(
    val requestedTargets: List<String>,
    val results: List<HotReloadTargetReport>
) {
    val succeededCount: Int
        get() = results.count { it.succeeded }

    val failedCount: Int
        get() = results.count { !it.succeeded }
}
