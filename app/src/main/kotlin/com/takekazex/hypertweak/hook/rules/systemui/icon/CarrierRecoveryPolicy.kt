package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Binding evidence, deliberately separate from device-visible acceptance. */
internal data class CarrierRecoveryEvidence(
    val environmentReady: Boolean,
    val hosts: Int,
    val rows: Int,
    val artworkReady: Boolean,
    val wifiBound: Boolean,
    val mobileBound: Boolean
) {
    val missing: List<String>
        get() = buildList {
            if (!environmentReady) add("environment")
            if (hosts == 0) add("host")
            if (hosts > 0 && rows != hosts * CarrierBlockPolicy.ROW_COUNT) add("rows")
            if (!artworkReady) add("artwork")
            if (!wifiBound) add("wifi")
            if (!mobileBound) add("mobile")
        }
}

internal object CarrierRecoveryPolicy {
    const val MAX_ATTEMPTS = 30
    const val RETRY_DELAY_MS = 500L
    enum class Decision { PREPARED, RETRY, EXHAUSTED, RETIRED }

    fun decide(current: Boolean, attempt: Int, evidence: CarrierRecoveryEvidence): Decision = when {
        !current -> Decision.RETIRED
        evidence.missing.isEmpty() -> Decision.PREPARED
        attempt >= MAX_ATTEMPTS -> Decision.EXHAUSTED
        else -> Decision.RETRY
    }
}
