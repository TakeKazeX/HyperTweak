package com.takekazex.hypertweak.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.UserHandle

/** Identity-sharing transport used by the common settings channel and native output adapters. */
internal object SettingsTransport {
    const val MODULE = "com.takekazex.hypertweak"
    const val SYSTEM_UI = "com.android.systemui"
    private const val SYSTEM_REQUEST_PERMISSION = "android.permission.INTERACT_ACROSS_USERS"
    private val options by lazy { Class.forName("android.app.BroadcastOptions") }
    private val make by lazy { options.getMethod("makeBasic") }
    private val identity by lazy { options.getMethod("setShareIdentityEnabled", Boolean::class.javaPrimitiveType) }
    private val bundle by lazy { options.getMethod("toBundle") }
    private val user by lazy { Context::class.java.getMethod("getUser") }
    private val sendAsUser by lazy { Context::class.java.getMethod("sendBroadcastAsUser",
        Intent::class.java, UserHandle::class.java, String::class.java, Bundle::class.java) }
    fun send(context: Context, intent: Intent) {
        val value = make.invoke(null)
        identity.invoke(value, true)
        val extras = bundle.invoke(value) as Bundle
        // Persistent privileged apps (including SystemUI) are checked as system
        // senders even with an app UID. Use a permission-protected endpoint for
        // hosts already holding this platform permission; grant no new permissions.
        val target = if (intent.action == RuntimeSettingsProtocol.REQUEST && intent.`package` == MODULE &&
            context.checkSelfPermission(SYSTEM_REQUEST_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            Intent(intent).setComponent(ComponentName(MODULE,
                "$MODULE.hook.SystemRuntimeSettingsSyncReceiver"))
        } else intent
        if (Process.myUid() == Process.SYSTEM_UID) {
            // Preserve the context's user, including system contexts created for a
            // particular user. Do not widen this to ALL/CURRENT or drop shared identity.
            sendAsUser.invoke(context, target, user.invoke(context) as UserHandle, null, extras)
        } else {
            context.sendBroadcast(target, null, extras)
        }
    }
    fun trusted(context: Context, senderPackage: String?, senderUid: Int, expected: String): Boolean =
        senderPackage == expected && senderUid >= 0 && runCatching {
            context.packageManager.getPackageUid(expected, 0) == senderUid
        }.getOrDefault(false)

}

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
