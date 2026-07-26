package com.takekazex.hypertweak.hook.rules.systemui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.ProxyLaunchProtocol

/**
 * Starts activities that are not exported to third-party apps, from inside SystemUI.
 *
 * The Extend Unlock configuration screen lives in GMS and is not exported, so the module cannot
 * launch it directly. SystemUI runs as uid system and can.
 *
 * The receiver is guarded by the module's signature-level permission and resolves the target from
 * [TARGETS] rather than from anything in the broadcast — a receiver in this process must never
 * start a caller-supplied component.
 */
object ProxyLaunchHooker : StaticHooker() {
    private const val TAG = "ProxyLaunch"

    private val TARGETS = mapOf(
        ProxyLaunchProtocol.TARGET_EXTEND_UNLOCK to ComponentName(
            "com.google.android.gms",
            "com.google.android.gms.trustagent.TrustAgentSearchEntryPointActivity"
        )
    )

    private var registration: Pair<Context, BroadcastReceiver>? = null

    override fun onHook() {
        // Registered from HookEntry.onPackageReady once an application Context exists.
    }

    override fun onPrepareHotReload() {
        unregister()
    }

    fun register(context: Context) {
        if (registration != null) return
        val appContext = context.applicationContext ?: context

        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ProxyLaunchProtocol.ACTION) return
                    val key = intent.getStringExtra(ProxyLaunchProtocol.EXTRA_TARGET)
                    val component = TARGETS[key] ?: run {
                        DebugLog.w(TAG, "refused proxy launch for unknown target=$key")
                        return
                    }
                    runCatching {
                        ctx.startActivity(
                            Intent().apply {
                                setComponent(component)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        DebugLog.i(TAG, "proxied launch target=$key component=$component")
                    }.onFailure { t ->
                        DebugLog.e(TAG, "failed to proxy launch target=$key component=$component", t)
                    }
                }
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(ProxyLaunchProtocol.ACTION),
                ProxyLaunchProtocol.PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            registration = appContext to receiver
            DebugLog.d(TAG, "registered proxy launch receiver")
        } catch (t: Throwable) {
            DebugLog.e(TAG, "failed to register proxy launch receiver", t)
        }
    }

    private fun unregister() {
        registration?.let { (context, receiver) ->
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { t -> DebugLog.w(TAG, "failed to unregister proxy launch receiver", t) }
        }
        registration = null
    }
}
