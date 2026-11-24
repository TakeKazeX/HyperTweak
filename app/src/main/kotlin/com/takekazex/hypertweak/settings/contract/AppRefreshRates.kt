package com.takekazex.hypertweak.settings.contract

/**
 * Per-app refresh rates chosen on MiSettings' "custom high refresh apps" page.
 *
 * MiSettings writes them and PowerKeeper reads them. Both run as the system uid, so the value
 * lives in `Settings.Secure` (see `SecureSettingStore` for why host-side `Preferences` writes are
 * not used). Only rates between the host's own "off" (60 Hz) and "on" (global rate) are stored;
 * those two stay in Xiaomi's per-app switch.
 */
object AppRefreshRates {
    const val SECURE_KEY = "hypertweak_app_refresh_rates"

    fun parse(value: String?): Map<String, Int> {
        if (value.isNullOrBlank()) return emptyMap()
        return value.split(';').mapNotNull { entry ->
            val separator = entry.lastIndexOf(':')
            if (separator <= 0) return@mapNotNull null
            val pkg = entry.substring(0, separator).trim()
            val rate = entry.substring(separator + 1).trim().toIntOrNull() ?: return@mapNotNull null
            if (pkg.isEmpty() || rate <= 0) null else pkg to rate
        }.toMap()
    }

    fun format(rates: Map<String, Int>): String =
        rates.entries.filter { it.key.isNotBlank() && it.value > 0 }
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}:${it.value}" }
}
