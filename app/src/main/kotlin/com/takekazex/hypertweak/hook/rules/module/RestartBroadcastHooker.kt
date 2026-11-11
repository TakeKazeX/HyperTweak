package com.takekazex.hypertweak.hook.rules.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.hook.base.HostApplicationContext
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.restart.RestartProtocol
import java.util.concurrent.ConcurrentHashMap

object RestartBroadcastHooker : StaticHooker() {
    /** Installs no method hook by design: its receiver is registered from onPackageReady. */
    override val expectsHooks = false

    private val registeredPackages = ConcurrentHashMap.newKeySet<String>()
    private val receivers = ConcurrentHashMap<String, Pair<Context, BroadcastReceiver>>()

    override fun onHook() {
        // Registered from HookEntry.onPackageReady to avoid touching Application.attach.
    }

    override fun onPrepareHotReload() {
        unregisterAll()
    }

    fun register(context: Context) {
        val pkgName = context.packageName ?: return

        // Do not register inside our own app to avoid killing the manager UI.
        if (pkgName == "com.takekazex.hypertweak") return
        if (!registeredPackages.add(pkgName)) return
        // The attach-time context would key the receiver on a ContextImpl that hot reload cannot unregister.
        HostApplicationContext.whenPublished(context) { register(it, pkgName) }
    }

    private fun register(appContext: Context, pkgName: String) {
        // unregisterAll() clears the claim when hot reload retires this generation first.
        if (!registeredPackages.contains(pkgName) || receivers.containsKey(pkgName)) return
        try {
            val filter = IntentFilter(RestartProtocol.ACTION)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == RestartProtocol.ACTION) {
                        val restartSystemUi = intent.getBooleanExtra(RestartProtocol.EXTRA_SYSTEM_UI, false)
                        val restartMiuiHome = intent.getBooleanExtra(RestartProtocol.EXTRA_MIUI_HOME, false)
                        val restartSettings = intent.getBooleanExtra(RestartProtocol.EXTRA_SETTINGS, false)
                        val restartAod = intent.getBooleanExtra(RestartProtocol.EXTRA_AOD, false)
                        val restartSecurityCenter = intent.getBooleanExtra(RestartProtocol.EXTRA_SECURITY_CENTER, false)
                        val restartScanner = intent.getBooleanExtra(RestartProtocol.EXTRA_SCANNER, false)
                        val restartMilink = intent.getBooleanExtra(RestartProtocol.EXTRA_MILINK, false)
                        val restartBluetooth = intent.getBooleanExtra(RestartProtocol.EXTRA_BLUETOOTH, false)
                        val restartPowerkeeper = intent.getBooleanExtra(RestartProtocol.EXTRA_POWERKEEPER, false)
                        val restartGms = intent.getBooleanExtra(RestartProtocol.EXTRA_GMS, false)
                        val restartXmsf = intent.getBooleanExtra(RestartProtocol.EXTRA_XMSF, false)

                        // Dynamically discovered scope packages name themselves here; fixed targets
                        // still use their compact boolean flags for compatibility.
                        val namedPackages = intent.getStringArrayExtra(RestartProtocol.EXTRA_PACKAGES)

                        val shouldRestart = namedPackages?.contains(pkgName) == true || when (pkgName) {
                            "com.android.systemui" -> restartSystemUi
                            "com.miui.home" -> restartMiuiHome
                            "com.android.settings" -> restartSettings
                            "com.miui.aod" -> restartAod
                            "com.miui.securitycenter", "com.miui.securitycore" -> restartSecurityCenter
                            "com.xiaomi.scanner" -> restartScanner
                            "com.milink.service" -> restartMilink
                            "com.xiaomi.bluetooth" -> restartBluetooth
                            "com.miui.powerkeeper" -> restartPowerkeeper
                            "com.google.android.gms" -> restartGms
                            "com.xiaomi.xmsf" -> restartXmsf
                            else -> false
                        }

                        if (shouldRestart) {
                            DebugLog.i("RestartBroadcastHooker", "killing process $pkgName by restart broadcast")
                            Process.killProcess(Process.myPid())
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, RestartProtocol.PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
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
                if (t is IllegalArgumentException &&
                    t.message?.contains("Receiver not registered") == true
                ) {
                    DebugLog.d("RestartBroadcastHooker", "restart receiver already unregistered in $pkgName")
                } else {
                    DebugLog.w("RestartBroadcastHooker", "failed to unregister restart receiver in $pkgName", t)
                }
            }
        }
        receivers.clear()
        registeredPackages.clear()
    }
}
