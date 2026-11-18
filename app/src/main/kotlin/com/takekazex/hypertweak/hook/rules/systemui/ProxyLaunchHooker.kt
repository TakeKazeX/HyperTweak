package com.takekazex.hypertweak.hook.rules.systemui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ProxyLaunchProtocol

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
    /** Installs no method hook by design: its receiver is registered from onPackageReady. */
    override val expectsHooks = false

    private const val TAG = "ProxyLaunch"

    private val TARGETS = mapOf(
        ProxyLaunchProtocol.TARGET_EXTEND_UNLOCK to ComponentName(
            "com.google.android.gms",
            "com.google.android.gms.trustagent.TrustAgentSearchEntryPointActivity"
        )
    )

    private var registration: AutoCloseable? by generationState { null }

    override fun onHook() {
        // Registered from HookEntry.onPackageReady once an application Context exists.
    }

    @Synchronized
    fun register(context: Context) {
        if (registration != null) return
        registration = generationReceiver(
            context, IntentFilter(ProxyLaunchProtocol.ACTION), ContextCompat.RECEIVER_EXPORTED,
            permission = ProxyLaunchProtocol.PERMISSION,
        ) { ctx, intent -> launch(ctx, intent) }
        DebugLog.d(TAG, "registered proxy launch receiver")
    }

    private fun launch(ctx: Context, intent: Intent) {
        if (intent.action != ProxyLaunchProtocol.ACTION) return
        val key = intent.getStringExtra(ProxyLaunchProtocol.EXTRA_TARGET)
        val component = TARGETS[key] ?: run {
            DebugLog.w(TAG, "refused proxy launch for unknown target=$key")
            return
        }
        runCatching {
            ctx.startActivity(Intent().apply {
                setComponent(component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            DebugLog.i(TAG, "proxied launch target=$key component=$component")
        }.onFailure { t -> DebugLog.e(TAG, "failed to proxy launch target=$key component=$component", t) }
    }
}
