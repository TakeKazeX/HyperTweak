package com.takekazex.hypertweak.hook.base

import android.app.Activity
import java.util.WeakHashMap

/** Native recreation preserves saved state; callers choose a safe resume boundary. Main thread only. */
internal class HostActivityRefresh {
    private val pending = WeakHashMap<Activity, Boolean>()

    fun request(accept: (Activity) -> Boolean) {
        activities().filter(accept).forEach { pending[it] = true }
    }

    fun resume(activity: Activity) {
        if (pending[activity] != true || activity.isFinishing || activity.isDestroyed) return
        // Clear only after successful submission; failures remain retryable on the next resume.
        activity.recreate()
        pending.remove(activity)
    }

    fun clear() = pending.clear()

    companion object {
        fun activities(): List<Activity> {
            val type = Class.forName("android.app.ActivityThread")
            val thread = HostAccess.requirePublicMethod(type, "currentActivityThread", accessible = false).invoke(null)
                ?: return emptyList()
            val records = HostAccess.read(thread, "mActivities") as? Map<*, *> ?: return emptyList()
            return records.values.filterNotNull().mapNotNull { HostAccess.read(it, "activity") as? Activity }
                .filterNot { it.isDestroyed || it.isFinishing }
        }
    }
}
