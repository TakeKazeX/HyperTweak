package com.takekazex.hypertweak.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Identity-sharing transport used by the common settings channel and native output adapters. */
internal object SettingsTransport {
    const val MODULE = "com.takekazex.hypertweak"
    const val SYSTEM_UI = "com.android.systemui"
    private val options by lazy { Class.forName("android.app.BroadcastOptions") }
    private val make by lazy { options.getMethod("makeBasic") }
    private val identity by lazy { options.getMethod("setShareIdentityEnabled", Boolean::class.javaPrimitiveType) }
    private val bundle by lazy { options.getMethod("toBundle") }
    fun send(context: Context, intent: Intent) {
        val value = make.invoke(null)
        identity.invoke(value, true)
        context.sendBroadcast(intent, null, bundle.invoke(value) as Bundle)
    }
    fun trusted(context: Context, senderPackage: String?, senderUid: Int, expected: String): Boolean =
        senderPackage == expected && senderUid >= 0 && runCatching {
            context.packageManager.getPackageUid(expected, 0) == senderUid
        }.getOrDefault(false)

}

/** Only registered, framework-authenticated consumers may request their settings subset. */
class RuntimeSettingsSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RuntimeSettingsProtocol.REQUEST) return
        val sender = sentFromPackage ?: return
        if (sender !in com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups.targets ||
            !SettingsTransport.trusted(context, sender, sentFromUid, sender)) return
        RuntimeSettingsSignal.publishCommitted(sender)
    }
}
