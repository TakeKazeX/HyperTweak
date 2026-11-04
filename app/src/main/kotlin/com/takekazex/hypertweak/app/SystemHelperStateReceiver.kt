package com.takekazex.hypertweak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.SystemHelperPackage

/**
 * Re-applies the persisted SystemHelper choice after a reboot, this module's own update, or a
 * target package update/OTA. Package state is read first, so a target already in the desired
 * state, or an absent target, never invokes root.
 */
class SystemHelperStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val packageName = intent.data?.schemeSpecificPart
        val relevant = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REPLACED -> packageName == SystemHelperPackage.NAME
            else -> false
        }
        if (!relevant) return
        if (!runCatching {
                Preferences.getBoolean(PlatformSettingsKeys.KEY_SYSTEM_HELPER_MANAGED, false)
            }.getOrDefault(false)) return
        val desiredDisabled = runCatching {
            Preferences.getBoolean(PlatformSettingsKeys.KEY_SYSTEM_HELPER_DISABLED, false)
        }.getOrDefault(false)

        val pending = goAsync()
        Thread({
            try {
                val result = DefaultAppServices.systemHelper.reconcileBlocking(context, desiredDisabled)
                if (result.failure != null) {
                    DebugLog.w("SystemHelper", "deferred reconcile failed: " + result.failure + " " + result.detail)
                }
            } catch (e: Exception) {
                DebugLog.w("SystemHelper", "deferred reconcile exception", e)
            } finally {
                pending.finish()
            }
        }, "HyperTweak-SystemHelper").start()
    }
}
