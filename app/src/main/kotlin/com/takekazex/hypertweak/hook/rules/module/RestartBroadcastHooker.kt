package com.takekazex.hypertweak.hook.rules.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

object RestartBroadcastHooker : StaticHooker() {
    private val registeredPackages = ConcurrentHashMap.newKeySet<String>()
    private val receivers = ConcurrentHashMap<String, Pair<Context, BroadcastReceiver>>()

    override fun onHook() {
        // Registered from HookEntry.onPackageReady to avoid touching Application.attach.
    }

    override fun onPrepareHotReload() {
        unregisterAll()
    }

    fun register(context: Context) {
        val appContext = context.applicationContext ?: context
        val pkgName = appContext.packageName ?: return

        // Do not register inside our own app to avoid killing the manager UI.
        if (pkgName == "com.takekazex.hypertweak") return
        if (!registeredPackages.add(pkgName)) return

        try {
            val filter = IntentFilter("com.takekazex.hypertweak.ACTION_RESTART_SCOPE")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == "com.takekazex.hypertweak.ACTION_RESTART_SCOPE") {
                        val restartSystemUi = intent.getBooleanExtra("systemui", false)
                        val restartSettings = intent.getBooleanExtra("settings", false)
                        val restartAod = intent.getBooleanExtra("aod", false)
                        val restartSecurityCenter = intent.getBooleanExtra("securitycenter", false)
                        val restartScanner = intent.getBooleanExtra("scanner", false)
                        val restartMilink = intent.getBooleanExtra("milink", false)
                        val restartBluetooth = intent.getBooleanExtra("bluetooth", false)
                        val restartPowerkeeper = intent.getBooleanExtra("powerkeeper", false)

                        val shouldRestart = when (pkgName) {
                            "com.android.systemui" -> restartSystemUi
                            "com.android.settings" -> restartSettings
                            "com.miui.aod" -> restartAod
                            "com.miui.securitycenter" -> restartSecurityCenter
                            "com.xiaomi.scanner" -> restartScanner
                            "com.milink.service" -> restartMilink
                            "com.xiaomi.bluetooth" -> restartBluetooth
                            "com.miui.powerkeeper" -> restartPowerkeeper
                            else -> false
                        }

                        if (shouldRestart) {
                            DebugLog.w("RestartBroadcastHooker", "killing process $pkgName by restart broadcast")
                            Process.killProcess(Process.myPid())
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receivers[pkgName] = appContext to receiver
            DebugLog.d("RestartBroadcastHooker", "registered restart receiver in $pkgName")
        } catch (t: Throwable) {
            registeredPackages.remove(pkgName)
            DebugLog.e("RestartBroadcastHooker", "failed to register restart receiver in $pkgName", t)
        }
    }

    fun unregisterAll() {
        receivers.forEach { (pkgName, entry) ->
            runCatching {
                entry.first.unregisterReceiver(entry.second)
                DebugLog.d("RestartBroadcastHooker", "unregistered restart receiver in $pkgName")
            }.onFailure { t ->
                DebugLog.w("RestartBroadcastHooker", "failed to unregister restart receiver in $pkgName", t)
            }
        }
        receivers.clear()
        registeredPackages.clear()
    }
}
