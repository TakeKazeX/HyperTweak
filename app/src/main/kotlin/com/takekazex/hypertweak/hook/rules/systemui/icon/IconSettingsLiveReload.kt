package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog

/** Coalesce remote setting writes; retirement cancels pending work in the old module generation. */
internal class IconSettingsLiveReload(private val apply: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = true
    private var retries = 0
    private val task = object : Runnable {
        override fun run() {
            if (!active) return
            Preferences.invalidateRuntimeReadCache()
            runCatching { apply() }.onSuccess { ready ->
                if (!ready && active && retries++ < 4) main.postDelayed(this, 250L)
            }.onFailure { DebugLog.w("IconTuner", "live icon settings refresh failed", it) }
        }
    }

    fun request() {
        main.post {
            if (!active) return@post
            retries = 0
            main.removeCallbacks(task)
            main.postDelayed(task, 150L)
        }
    }

    fun cancel() { active = false; main.removeCallbacksAndMessages(null) }
    fun resume() { active = true; request() }
}

internal object IconSettingsPreferencePolicy {
    fun affectsIcons(key: String?): Boolean = key == null || key == "prefs_epoch" ||
        key.startsWith("icon_") || key.startsWith("stacked_s_") ||
        key.startsWith("systemui_cc_") || key.startsWith("statusbar_hide_carrier_") ||
        key.startsWith("systemui_ls_hide_carrier_") || key == "statusbar_region_sampling" ||
        key == "statusbar_notif_max" || key == "statusbar_notif_icon_max" ||
        key == "statusbar_ignore_sys_hide"
}
