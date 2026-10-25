package com.takekazex.hypertweak.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only registered, framework-authenticated consumers may request their settings subset. */
open class RuntimeSettingsSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RuntimeSettingsProtocol.REQUEST) return
        val sender = sentFromPackage ?: return
        if (sender !in com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups.targets ||
            !SettingsTransport.trusted(context, sender, sentFromUid, sender)) return
        RuntimeSettingsSignal.publishCommitted(sender)
    }
}

/** Permission-protected manifest endpoint; also starts a cold module process. */
class SystemRuntimeSettingsSyncReceiver : RuntimeSettingsSyncReceiver()
