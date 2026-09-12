package com.takekazex.hypertweak.util.update

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.takekazex.hypertweak.BuildConfig
import kotlin.math.abs

/** Reconciles installs after the package replacement has killed the previous app process. */
class UpdateRecoveryManager(context: Context) {
    private val appContext = context.applicationContext
    private val store = UpdateStateStore(appContext)
    private val installer = UpdateInstaller(appContext, store)

    fun reconcile(
        nowWallClock: Long = System.currentTimeMillis(),
        nowElapsed: Long = android.os.SystemClock.elapsedRealtime()
    ): CompletionNotice? {
        val arm = store.arm()
        if (arm == null) {
            store.cleanupExpiredFiles(nowWallClock)
            return null
        }

        val restarted = abs((nowWallClock - nowElapsed) - arm.bootSessionKey) > BOOT_KEY_TOLERANCE_MILLIS
        val age = if (restarted) {
            nowWallClock - arm.armedAtWallClock
        } else {
            nowElapsed - arm.armedAtElapsed
        }
        val installed = runCatching {
            appContext.packageManager.getPackageInfo(
                BuildConfig.APPLICATION_ID,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        }.getOrNull()
        val packageTimestampChanged = installed != null && installed.lastUpdateTime != arm.previousLastUpdateTime
        val recentPackageUpdate = hasRecentPackageUpdate(nowWallClock)

        if ((packageTimestampChanged || recentPackageUpdate) && installer.installedMatches(arm)) {
            runCatching { java.io.File(arm.apkPath).delete() }
            runCatching { java.io.File("${arm.apkPath}.json").delete() }
            store.clearArm()
            val notice = CompletionNotice(arm.targetVersionName, arm.targetVersionCode)
            store.markCompletionNotice(notice)
            store.cleanupExpiredFiles(nowWallClock)
            return notice
        }

        if (age >= RECOVERY_TIMEOUT_MILLIS || age < -BOOT_KEY_TOLERANCE_MILLIS) {
            store.clearArm()
        }
        store.cleanupExpiredFiles(nowWallClock)
        return null
    }

    private fun hasRecentPackageUpdate(nowWallClock: Long): Boolean {
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return false
        return runCatching {
            manager.getHistoricalProcessExitReasons(BuildConfig.APPLICATION_ID, 0, 5).any { reason ->
                reason.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED &&
                    nowWallClock - reason.timestamp in 0L..RECOVERY_TIMEOUT_MILLIS
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val RECOVERY_TIMEOUT_MILLIS = 30L * 60L * 1000L
        const val BOOT_KEY_TOLERANCE_MILLIS = 5L * 60L * 1000L
    }
}
