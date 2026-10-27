package com.takekazex.hypertweak.util.diagnostics

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Separate local file: never backed up, restored, or remotely writable through Preferences. */
internal object DebugAccessSession {
    private const val DURATION_MS = 30 * 60 * 1000L
    private val changed = MutableStateFlow(0L)
    val changes = changed.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("debug_access_session", Context.MODE_PRIVATE)
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    @Suppress("DEPRECATION")
    private fun install(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    @Synchronized fun remaining(context: Context): Long {
        val p = prefs(context)
        return remainingFor(p.getLong("until", 0), SystemClock.elapsedRealtime(),
            p.getInt("boot", -2), boot(context), p.getLong("install", -1), install(context))
    }
    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().clear().apply {
            if (enabled) {
                check(boot(context) >= 0) { "Boot identity unavailable" }
                putInt("boot", boot(context)).putLong("install", install(context))
                putLong("until", SystemClock.elapsedRealtime() + DURATION_MS)
            }
        }.commit()) { "Unable to save debug authorization" }
        changed.value += 1
    }
    internal fun remainingFor(until: Long, now: Long, grantedBoot: Int, currentBoot: Int,
        grantedInstall: Long, currentInstall: Long): Long {
        if (currentBoot < 0 || grantedBoot != currentBoot || grantedInstall != currentInstall || until <= now) return 0
        return (until - now).coerceAtMost(DURATION_MS)
    }
    fun requireRoot(uid: Int, remaining: Long) {
        check(uid == 0) { "ROOT_REQUIRED" }
        check(remaining > 0) { "DEBUG_ACCESS_DISABLED" }
    }
}
