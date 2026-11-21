package com.takekazex.hypertweak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.takekazex.hypertweak.settings.contract.AnalyticsSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.AnalyticsPackageControl

class AnalyticsPackageStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AnalyticsPackageControl.canManage()) return
        val relevant = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> true
            Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED ->
                intent.data?.schemeSpecificPart == AnalyticsSettingsKeys.PACKAGE
            else -> false
        }
        if (!relevant) return
        val pending = goAsync()
        Thread({
            try {
                AnalyticsPackageControl.reconcile(context)
            } catch (e: Exception) {
                DebugLog.w("AnalyticsPackage", "Deferred reconciliation failed", e)
            } finally {
                pending.finish()
            }
        }, "HyperTweak-AnalyticsPackage").start()
    }
}
