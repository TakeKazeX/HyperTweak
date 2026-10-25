package com.takekazex.hypertweak.settings.contract

/** Persisted UI/host option protocol; values are stable across upgrades. */
object LockscreenChargingOptions {
    const val FIELD_WATTAGE = 1
    const val FIELD_VOLTAGE = 2
    const val FIELD_CURRENT = 4
    const val FIELD_TEMPERATURE = 8
    const val DEFAULT_FIELDS = FIELD_WATTAGE or FIELD_VOLTAGE or FIELD_CURRENT or FIELD_TEMPERATURE
    const val DEFAULT_INTERVAL_MS = 2_000
    const val MIN_INTERVAL_MS = 1_000
    const val MAX_INTERVAL_MS = 10_000
}
