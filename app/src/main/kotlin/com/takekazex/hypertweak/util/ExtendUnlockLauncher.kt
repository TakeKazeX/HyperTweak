package com.takekazex.hypertweak.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Opens GMS's Extend Unlock (Smart Lock) configuration screen, which HyperOS gives no route to.
 *
 * `TrustAgentSearchEntryPointActivity` is a trampoline that finishes immediately and hands off to
 * `ConfirmUserCredentialAndStartActivity` for the lock-screen credential prompt. It is exported on
 * the current baseline, so the direct launch is the normal path; the SystemUI proxy only covers
 * builds where it is not.
 */
object ExtendUnlockLauncher {
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val ENTRY_ACTIVITY = "com.google.android.gms.trustagent.TrustAgentSearchEntryPointActivity"

    fun launch(context: Context) {
        val direct = Intent().apply {
            component = ComponentName(GMS_PACKAGE, ENTRY_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(direct)
            return
        } catch (_: Exception) {
            // Not exported on this build, or Play Services is missing.
        }

        try {
            context.sendBroadcast(
                Intent(ProxyLaunchProtocol.ACTION).apply {
                    setPackage("com.android.systemui")
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(ProxyLaunchProtocol.EXTRA_TARGET, ProxyLaunchProtocol.TARGET_EXTEND_UNLOCK)
                }
            )
            Toast.makeText(context, "Opening Extend Unlock via SystemUI", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Unable to open Extend Unlock. Check that Google Play services is installed and " +
                    "SystemUI is in the module's scope",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
