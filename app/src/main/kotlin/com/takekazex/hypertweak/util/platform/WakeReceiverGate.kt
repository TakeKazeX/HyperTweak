package com.takekazex.hypertweak.util.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Enables a manifest receiver only while its feature needs it. A receiver for boot/package
 * broadcasts cold-starts the module process even when it then returns, and HyperOS records each
 * such start as an auto-start. The manifest keeps every gated receiver enabled, so a component
 * that was never synchronized still receives the next broadcast and disables itself there.
 */
internal object WakeReceiverGate {
    const val SYSTEM_HELPER = "com.takekazex.hypertweak.app.SystemHelperStateReceiver"
    const val WIRELESS_ADB = "com.takekazex.hypertweak.app.WirelessAdbStateReceiver"

    fun set(context: Context, receiver: String, needed: Boolean) {
        runCatching {
            val component = ComponentName(context.packageName, receiver)
            val packages = context.packageManager
            val desired = if (needed) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packages.getComponentEnabledSetting(component) == desired) return
            packages.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
            DebugLog.i("WakeReceiver", "${receiver.substringAfterLast('.')} enabled=$needed")
        }.onFailure { DebugLog.w("WakeReceiver", "update failed receiver=$receiver needed=$needed", it) }
    }
}
