package com.takekazex.hypertweak.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.takekazex.hypertweak.util.DebugLog

/** SystemUI owns remote Preferences; HYOS receives a complete, identity-sharing snapshot. */
internal class NativeRuleStatePublisher {
    private val handler = Handler(Looper.getMainLooper())
    private val token = Any()
    private var context: Context? = null
    private var revision = 0L
    private var receiverRegistered = false
    @Volatile private var closed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_USER_UNLOCKED) {
                publish()
            }
        }
    }

    fun attach(context: Context) {
        val application = context.applicationContext ?: context
        handler.post {
            if (closed) return@post
            if (this.context === application) return@post
            detachContext()
            this.context = application
            runCatching {
                application.registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                }, Context.RECEIVER_EXPORTED)
                receiverRegistered = true
            }.onFailure { DebugLog.w("NativeRules", "boot settings publisher registration failed", it) }
            publish()
        }
    }

    fun publish() {
        handler.post {
            if (closed) return@post
            val target = context ?: return@post
            val snapshot = Preferences.nativeRuleSnapshot() ?: return@post
            revision = SystemClock.elapsedRealtimeNanos().coerceAtLeast(revision + 1L)
            val current = revision
            handler.removeCallbacksAndMessages(token)
            // Finite replays cover native binding/AOT preparation racing cold SystemUI
            // startup. No heartbeat, input poll, or unconditional repeating timer.
            for (delay in NativeRuleStateSnapshot.REPLAY_DELAYS_MS) {
                handler.postAtTime({
                    if (context !== target || revision != current) return@postAtTime
                    send(target, snapshot, current)
                }, token, SystemClock.uptimeMillis() + delay)
            }
        }
    }

    private fun send(context: Context, snapshot: NativeRuleStateSnapshot, revision: Long) {
        runCatching {
            val intent = Intent("com.android.systemui.fsgesture").setPackage("com.miui.home")
                .putExtra("hypertweak_rule_schema", 2)
                .putExtra("hypertweak_rule_revision", revision)
                .putExtra("hypertweak_rule_hide_clear", snapshot.hidden)
                .putExtra("hypertweak_rule_folder_columns", snapshot.columns)
                .putExtra("hypertweak_rule_contextual_search", snapshot.contextualSearch)
                .putExtra("sender_uid", Process.myUid())
            context.sendBroadcast(intent, null, identityOptions())
            DebugLog.d("NativeRules", "published native settings revision=$revision columns=${snapshot.columns} hidden=${snapshot.hidden}")
        }.onFailure { DebugLog.w("NativeRules", "native settings broadcast failed", it) }
    }

    // Cache hidden platform reflection outside the settings/input hot paths.
    private val optionsClass by lazy { Class.forName("android.app.BroadcastOptions") }
    private val makeOptions by lazy { optionsClass.getMethod("makeBasic") }
    private val shareIdentity by lazy { optionsClass.getMethod("setShareIdentityEnabled", Boolean::class.javaPrimitiveType) }
    private val bundleOptions by lazy { optionsClass.getMethod("toBundle") }
    private fun identityOptions(): Bundle {
        val options = makeOptions.invoke(null)
        shareIdentity.invoke(options, true)
        return bundleOptions.invoke(options) as Bundle
    }

    private fun detachContext() {
        val old = context
        context = null
        handler.removeCallbacksAndMessages(token)
        if (old != null && receiverRegistered) runCatching { old.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    fun close() {
        // Retire queued attachment/publication too, before an old API-102 class
        // generation can register another receiver after hot reload preparation.
        closed = true
        handler.removeCallbacksAndMessages(null)
        detachContext()
    }
}
