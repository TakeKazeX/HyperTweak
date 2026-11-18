package com.takekazex.hypertweak.hook.rules.shared

import android.app.ActivityManager

/**
 * The live task list, for code running inside privileged SystemUI.
 *
 * `getRunningTasks` is deprecated because third-party callers only see their own tasks. SystemUI
 * holds `REAL_GET_TASKS` and still receives the full, most-recent-first list with `topActivity`.
 * No public, non-deprecated API returns that; UsageStats is not an equivalent source.
 * This is the one place that acknowledges the deprecation.
 */
object PrivilegedRunningTasks {
    @JvmStatic
    @Suppress("DEPRECATION")
    fun query(manager: ActivityManager, maxNum: Int): List<ActivityManager.RunningTaskInfo>? =
        manager.getRunningTasks(maxNum)
}
