package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

object RestartUtils {
    /**
     * Restarts packages that have no [RestartScopeSelection] field, such as the user-selected input
     * methods. The in-process receiver is already registered in every hooked package, so a newly
     * scoped app is reachable without any extra wiring.
     *
     * Returns the launched [Job]; callers that must sequence work after the restart (for example
     * revoking a scope whose hooker has to run one last time) can `join()` it.
     */
    fun forceStopPackages(
        context: Context,
        coroutineScope: CoroutineScope,
        packages: Set<String>
    ): Job {
        if (packages.isEmpty()) return SupervisorJob().apply { complete() }
        return coroutineScope.launch {
            val intent = Intent(RestartProtocol.ACTION).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(RestartProtocol.EXTRA_PACKAGES, packages.toTypedArray())
            }
            // No receiver permission: that argument demands the *receiver* hold it, and the hooked
            // system apps never will. Senders are already restricted by the receivers'
            // broadcastPermission, which this app now holds.
            runCatching { context.sendBroadcast(intent) }

            val rootSuccess = withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec("su")
                    process.outputStream.bufferedWriter().use { writer ->
                        packages.forEach { writer.write("am force-stop $it\n") }
                        writer.write("exit\n")
                        writer.flush()
                    }
                    if (!process.waitFor(8, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        DebugLog.e("RestartUtils", "root package restart timed out")
                        false
                    } else {
                        DebugLog.d("RestartUtils", "root package restart exit=${process.exitValue()}")
                        process.exitValue() == 0
                    }
                } catch (e: Exception) {
                    DebugLog.e("RestartUtils", "root package restart failed", e)
                    false
                }
            }

            withContext(Dispatchers.Main) {
                val message = if (rootSuccess) {
                    "Restarted ${packages.size} app(s) via Root"
                } else {
                    "Broadcast sent to restart ${packages.size} app(s)"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun restartScope(
        context: Context,
        coroutineScope: CoroutineScope,
        selection: RestartScopeSelection
    ) {
        restartScope(
            context = context,
            coroutineScope = coroutineScope,
            systemUi = selection.systemUi,
            miuiHome = selection.miuiHome,
            settings = selection.settings,
            aod = selection.aod,
            securityCenter = selection.securityCenter,
            scanner = selection.scanner,
            milink = selection.milink,
            bluetooth = selection.bluetooth,
            powerkeeper = selection.powerkeeper
        )
    }

    fun restartScope(
        context: Context,
        coroutineScope: CoroutineScope,
        systemUi: Boolean,
        miuiHome: Boolean = false,
        settings: Boolean,
        aod: Boolean,
        securityCenter: Boolean,
        scanner: Boolean,
        milink: Boolean,
        bluetooth: Boolean,
        powerkeeper: Boolean = false
    ) {
        if (!systemUi && !miuiHome && !settings && !aod && !securityCenter && !scanner && !milink && !bluetooth && !powerkeeper) return

        coroutineScope.launch {
            // 1. Send broadcast to active hook receivers
            val intent = Intent(RestartProtocol.ACTION).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(RestartProtocol.EXTRA_SYSTEM_UI, systemUi)
                putExtra(RestartProtocol.EXTRA_MIUI_HOME, miuiHome)
                putExtra(RestartProtocol.EXTRA_SETTINGS, settings)
                putExtra(RestartProtocol.EXTRA_AOD, aod)
                putExtra(RestartProtocol.EXTRA_SECURITY_CENTER, securityCenter)
                putExtra(RestartProtocol.EXTRA_SCANNER, scanner)
                putExtra(RestartProtocol.EXTRA_MILINK, milink)
                putExtra(RestartProtocol.EXTRA_BLUETOOTH, bluetooth)
                putExtra(RestartProtocol.EXTRA_POWERKEEPER, powerkeeper)
            }
            // No receiver permission: that argument demands the *receiver* hold it, and the hooked
            // system apps never will. Senders are already restricted by the receivers'
            // broadcastPermission, which this app now holds.
            runCatching { context.sendBroadcast(intent) }

            // 2. Try executing root shell commands to terminate target processes
            val rootSuccess = withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec("su")
                    process.outputStream.bufferedWriter().use { writer ->
                        if (systemUi) {
                            writer.write("pkill -f com.android.systemui\n")
                        }
                        if (miuiHome) {
                            writer.write("am force-stop com.miui.home\n")
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
                    val completed = process.waitFor(8, TimeUnit.SECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        DebugLog.e("RestartUtils", "root restart timed out")
                        false
                    } else {
                        val stderr = process.errorStream.bufferedReader().use { it.readText() }
                        if (stderr.isNotBlank()) DebugLog.e("RestartUtils", "root stderr: $stderr")
                        DebugLog.d("RestartUtils", "root restart exit=${process.exitValue()}")
                        process.exitValue() == 0
                    }
                } catch (e: Exception) {
                    DebugLog.e("RestartUtils", "root restart failed", e)
                    false
                }
            }

            // 3. Provide feedback toast to user
            withContext(Dispatchers.Main) {
                val targets = buildList {
                    if (systemUi) add("SystemUI")
                    if (miuiHome) add("MiuiHome")
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
