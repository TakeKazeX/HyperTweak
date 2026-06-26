package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RestartUtils {
    fun restartScope(
        context: Context,
        coroutineScope: CoroutineScope,
        systemUi: Boolean,
        settings: Boolean,
        aod: Boolean,
        securityCenter: Boolean,
        scanner: Boolean,
        milink: Boolean,
        bluetooth: Boolean,
        powerkeeper: Boolean = false
    ) {
        if (!systemUi && !settings && !aod && !securityCenter && !scanner && !milink && !bluetooth && !powerkeeper) return

        coroutineScope.launch {
            // 1. Send broadcast to active hook receivers
            val intent = Intent("com.takekazex.hypertweak.ACTION_RESTART_SCOPE").apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra("systemui", systemUi)
                putExtra("settings", settings)
                putExtra("aod", aod)
                putExtra("securitycenter", securityCenter)
                putExtra("scanner", scanner)
                putExtra("milink", milink)
                putExtra("bluetooth", bluetooth)
                putExtra("powerkeeper", powerkeeper)
            }
            context.sendBroadcast(intent)

            // 2. Try executing root shell commands to terminate target processes
            val rootSuccess = withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec("su")
                    process.outputStream.bufferedWriter().use { writer ->
                        if (systemUi) {
                            writer.write("pkill -f com.android.systemui\n")
                        }
                        if (settings) {
                            writer.write("am force-stop com.android.settings\n")
                        }
                        if (aod) {
                            writer.write("am force-stop com.miui.aod\n")
                        }
                        if (securityCenter) {
                            writer.write("am force-stop com.miui.securitycenter\n")
                        }
                        if (scanner) {
                            writer.write("am force-stop com.xiaomi.scanner\n")
                        }
                        if (milink) {
                            writer.write("am force-stop com.milink.service\n")
                        }
                        if (bluetooth) {
                            writer.write("am force-stop com.xiaomi.bluetooth\n")
                        }
                        if (powerkeeper) {
                            writer.write("am force-stop com.miui.powerkeeper\n")
                        }
                        writer.write("exit\n")
                        writer.flush()
                    }
                    process.waitFor() == 0
                } catch (e: Exception) {
                    false
                }
            }

            // 3. Provide feedback toast to user
            withContext(Dispatchers.Main) {
                val targets = buildList {
                    if (systemUi) add("SystemUI")
                    if (settings) add("Settings")
                    if (aod) add("AOD")
                    if (securityCenter) add("Security")
                    if (scanner) add("Scanner")
                    if (milink) add("MiLink")
                    if (bluetooth) add("Bluetooth")
                    if (powerkeeper) add("PowerKeeper")
                }.joinToString(", ")

                if (rootSuccess) {
                    Toast.makeText(context, "Restarted $targets via Root", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Broadcast sent to restart $targets", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
