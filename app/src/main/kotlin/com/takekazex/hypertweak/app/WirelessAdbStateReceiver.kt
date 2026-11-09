package com.takekazex.hypertweak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.takekazex.hypertweak.util.platform.AdbTcpPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WirelessAdbStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!AdbTcpPort.shouldRestore(context)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AdbTcpPort.restore(context.applicationContext, afterBoot = intent.action == Intent.ACTION_BOOT_COMPLETED)
            } finally {
                pending.finish()
            }
        }
    }
}
