package com.takekazex.hypertweak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.SystemHelperPackage
import com.takekazex.hypertweak.util.platform.WakeReceiverGate

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
        val managed = readManaged() ?: return
        if (!managed) {
            WakeReceiverGate.set(context, WakeReceiverGate.SYSTEM_HELPER, false)
            return
        }

        val pending = goAsync()
        Thread({
            try {
                val result = DefaultAppServices.systemHelper.reconcileBlocking(context)
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

    companion object {
        // A failed read keeps the receiver as it is, so OTA recovery is never lost to a transient error.
        private fun readManaged(): Boolean? = runCatching {
            Preferences.getBoolean(PlatformSettingsKeys.KEY_SYSTEM_HELPER_MANAGED, false)
        }.getOrNull()

        fun syncWakeReceiver(context: Context) {
            WakeReceiverGate.set(context, WakeReceiverGate.SYSTEM_HELPER, readManaged() ?: return)
        }
    }
}
