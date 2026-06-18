package com.takekazex.hypertweak.hook.rules.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.hook.base.StaticHooker

object RestartBroadcastHooker : StaticHooker() {
    override fun onHook() {
        val clzApplication = "android.app.Application".toClassOrNull() ?: return
        clzApplication.findMethodOrNull {
            name("attach")
            parameterTypes(Context::class.java)
        }?.hook {
            after { param ->
                val context = param.args[0] as? Context ?: return@after
                val pkgName = context.packageName ?: return@after

                // Do not register inside our own app to avoid killing the manager UI
                if (pkgName == "com.takekazex.hypertweak") return@after

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

                                val shouldRestart = when (pkgName) {
                                    "com.android.systemui" -> restartSystemUi
                                    "com.android.settings" -> restartSettings
                                    "com.miui.aod" -> restartAod
                                    "com.miui.securitycenter" -> restartSecurityCenter
                                    "com.xiaomi.scanner" -> restartScanner
                                    else -> false
                                }

                                if (shouldRestart) {
                                    Log.d("HyperTweak", "RestartBroadcastHooker: killing process $pkgName")
                                    Process.killProcess(Process.myPid())
                                }
                            }
                        }
                    }
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                    Log.d("HyperTweak", "RestartBroadcastHooker: registered restart receiver in $pkgName")
                } catch (t: Throwable) {
                    Log.e("HyperTweak", "Failed to register restart receiver in $pkgName", t)
                }
            }
        }
    }
}
