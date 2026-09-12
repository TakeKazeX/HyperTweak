package com.takekazex.hypertweak.util

import android.content.Context
import androidx.core.content.edit

/**
 * Keeps the app-local history used to rank the restart picker.
 *
 * This is UI metadata rather than module configuration, so it deliberately lives outside the
 * LSPosed/remote preferences. It survives a normal settings reset and is only cleared when the
 * module's app data is cleared or the app is uninstalled.
 */
object RestartHistory {
    private const val PREFERENCES_NAME = "hypertweak_restart_history"
    private const val COUNT_PREFIX = "count:"
    private val lock = Any()

    fun counts(context: Context, packages: Collection<String>): Map<String, Int> {
        val normalized = packages.mapNotNull(::normalize).distinct()
        if (normalized.isEmpty()) return emptyMap()

        val preferences = historyPreferences(context)
        return synchronized(lock) {
            normalized.associateWith { packageName ->
                preferences.getInt(key(packageName), 0).coerceAtLeast(0)
            }
        }
    }

    /** Records one restart attempt for each distinct target package. */
    fun record(context: Context, packages: Collection<String>) {
        val normalized = packages.mapNotNull(::normalize).toSet()
        if (normalized.isEmpty()) return

        val preferences = historyPreferences(context)
        synchronized(lock) {
            preferences.edit {
                normalized.forEach { packageName ->
                    val current = preferences.getInt(key(packageName), 0).coerceAtLeast(0)
                    putInt(
                        key(packageName),
                        if (current == Int.MAX_VALUE) Int.MAX_VALUE else current + 1
                    )
                }
            }
        }
    }

    private fun historyPreferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun key(packageName: String): String = "$COUNT_PREFIX$packageName"

    private fun normalize(packageName: String?): String? =
        packageName?.trim()?.takeIf { it.isNotEmpty() }
}
