package com.takekazex.hypertweak.hook.rules.systemui

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.SystemClock
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.atomic.AtomicBoolean

/** Task evidence for the launcher Home gate, independent of hook registration. */
internal class GestureBarHomeProbe(
    private val context: () -> Context?,
    private val handler: () -> Handler,
    private val enabled: () -> Boolean,
    private val active: () -> Boolean,
    private val overviewOnScreen: () -> Boolean,
    private val repaint: () -> Unit,
) {
    private companion object {
        const val TAG = "HideBottomBar"
        const val PRIMARY_DISPLAY = 0
        const val MAX_TASKS_FOR_PROBE = 8
    }
    /**
     * Whether the launcher's Home surface owns the primary display right now.
     *
     * Read by the draw gate on every frame, so it is a plain volatile flag; the platform query that
     * produces it runs on the events that can change the answer (see [refreshLauncherHome]).
     */
    @Volatile var launcherHomeVisible = false

    /** Uptime millis of the last completed launcher-Home probe; 0 means "never asked". */
    @Volatile var launcherHomeProbedAt = 0L

    /** Guards against stacking probes while one is already queued on the main thread. */
    private val launcherHomeProbePending = AtomicBoolean(false)

    /** The queued probe, so a retired generation can drop it instead of running it later. */
    @Volatile private var launcherHomeProbeTask: Runnable? = null

    /** Resolved HOME components of the default launcher, cached until resolution succeeds. */
    @Volatile private var launcherHomeComponents: Set<String> = emptySet()

    fun refreshLauncherHome(reason: String) {
        if (!enabled() || !active()) return
        // Never inline. The callers are platform callbacks (a binder thread, or the keyguard state
        // machine on the main thread) and the draw gate itself; asking the activity manager inside
        // either would put that round trip on a path that must stay cheap. The gate keeps its
        // previous answer for the current frame and repaints as soon as the probe returns.
        if (!launcherHomeProbePending.compareAndSet(false, true)) return
        val probe = Runnable {
            launcherHomeProbeTask = null
            try {
                probeLauncherHome(reason)
            } finally {
                launcherHomeProbePending.set(false)
            }
        }
        launcherHomeProbeTask = probe
        handler().post(probe)
    }

    private fun probeLauncherHome(reason: String) {
        if (!active()) return
        val context = context() ?: return
        val keyguardLocked = runCatching {
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)
        val probe = LauncherHomeProbe(
            topTaskIsHome = runCatching { queryTopTaskIsLauncherHome(context) }.getOrElse {
                DebugLog.d(TAG, "launcher Home probe failed: ${it.javaClass.simpleName}")
                null
            },
            keyguardLocked = keyguardLocked
        )
        launcherHomeProbedAt = SystemClock.uptimeMillis()
        // HYOS runs Recents inside the launcher activity, so the newest task keeps being Home
        // while the recent-task list owns the bottom of the screen. Recents must keep deciding
        // that surface itself; the launcher's authenticated Overview report outranks Shell's map,
        // which keeps answering false for the Dart implementation.
        val overviewOnScreen = overviewOnScreen()
        val next = LauncherHomePolicy.resolve(probe, overviewOnScreen, launcherHomeVisible)
        if (next == launcherHomeVisible) return
        launcherHomeVisible = next
        DebugLog.i(TAG, "launcher Home visible=$next ($reason) " +
            "top=${probe.topTaskIsHome} keyguard=${probe.keyguardLocked} " +
            "overview=$overviewOnScreen")
        // The gate lives in onDraw, and skipping a draw never schedules the next frame.
        repaint()
    }

    /** Whether the newest task of the primary display is the launcher's HOME component. */
    private fun queryTopTaskIsLauncherHome(context: Context): Boolean? {
        val homes = launcherHomeComponents(context)
        if (homes.isEmpty()) {
            DebugLog.d(TAG, "launcher Home probe unavailable: no resolved HOME component")
            return null
        }
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        // REAL_GET_TASKS is declared by SystemUI; an unreadable list is not an answer.
        // Same query shape as the shipped back-gesture runtime: most recent first, and the first
        // task that belongs to this display is the one on screen. Visibility is deliberately not
        // filtered on - skipping a task would fall through to the launcher sitting behind it.
        val tasks = manager.getRunningTasks(MAX_TASKS_FOR_PROBE) ?: return null
        for (task in tasks) {
            if (taskDisplayId(task)?.let { it != PRIMARY_DISPLAY } == true) continue
            val top = task.topActivity ?: continue
            return LauncherHomePolicy.matchesHome(
                LauncherHomePolicy.flatten(top.packageName, top.className), homes
            )
        }
        // No task reported for this display at all: the platform has no answer right now.
        return null
    }

    /**
     * The display a running task belongs to, or null when the platform does not expose it.
     *
     * `TaskInfo.displayId` and `getDisplayId()` are both hidden from the SDK; SystemUI itself
     * reads them, so one of the two shapes is present on every supported build.
     */
    private fun taskDisplayId(task: ActivityManager.RunningTaskInfo): Int? {
        (runCatching { HostAccess.read(task, "displayId") }.getOrNull() as? Int)
            ?.let { return it }
        return runCatching {
            HostAccess.method(task, "getDisplayId")?.invoke(task) as? Int
        }.getOrNull()
    }

    /**
     * Resolves the launcher's HOME components.
     *
     * The same default resolution the shipped back-gesture runtime uses, including the alias case:
     * a launcher may publish its desktop through a target activity, and both flattened names are
     * accepted. Resolution is cached only after it succeeds, so a package-manager answer that
     * arrives late (a launcher update, a user switch) is retried instead of pinning an empty set.
     */
    private fun launcherHomeComponents(context: Context): Set<String> {
        launcherHomeComponents.takeIf { it.isNotEmpty() }?.let { return it }
        val resolved = runCatching {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(LauncherHomePolicy.LAUNCHER_PACKAGE)
            val info = context.packageManager.resolveActivity(
                intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )?.activityInfo
            if (info == null || info.packageName != LauncherHomePolicy.LAUNCHER_PACKAGE) emptySet()
            else buildSet {
                add(LauncherHomePolicy.flatten(info.packageName, info.name))
                info.targetActivity?.let {
                    add(LauncherHomePolicy.flatten(info.packageName, it))
                }
            }
        }.onFailure { DebugLog.w(TAG, "failed to resolve the launcher HOME component", it) }
            .getOrDefault(emptySet())
        if (resolved.isNotEmpty()) launcherHomeComponents = resolved
        return resolved
    }

    fun retire() {
        launcherHomeVisible = false
        launcherHomeProbedAt = 0L
        launcherHomeProbeTask?.let { handler().removeCallbacks(it) }
        launcherHomeProbeTask = null
        launcherHomeProbePending.set(false)
        launcherHomeComponents = emptySet()
    }
}
