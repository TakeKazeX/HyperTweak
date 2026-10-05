package com.takekazex.hypertweak.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private val ledger = NativeSnapshotLedger()
    private var waiting: String? = null
    private var forwarded: NativeRuleStateSnapshot? = null
    @Volatile private var closed = false
    fun saveSnapshot(): Array<Any>? = ledger.save()
    fun restoreSnapshot(state: Any?) = ledger.restore(state)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == NativeRuleProtocol.CHANGED) {
                if (!NativeRuleProtocol.trusted(context, sentFromPackage, sentFromUid, NativeRuleProtocol.MODULE)) return
                val snapshot = NativeRuleProtocol.decode(intent) ?: run {
                    DebugLog.w("NativeRules", "rejected incomplete app settings notification")
                    return
                }
                if (ledger.offer(snapshot)) publish()
                return
            }
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_USER_UNLOCKED) {
                requestSnapshot(context)
                publish()
            }
        }
    }

    fun attach(context: Context) {
        val application = context.applicationContext ?: context
        handler.post {
            if (closed) return@post
            if (this.context === application && receiverRegistered) return@post
            detachContext()
            this.context = application
            runCatching {
                application.registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(NativeRuleProtocol.CHANGED)
                }, Context.RECEIVER_EXPORTED)
                receiverRegistered = true
            }.onFailure { DebugLog.w("NativeRules", "boot settings publisher registration failed", it) }
            DebugLog.i("NativeRules", "SystemUI native settings publisher attached observer=$receiverRegistered")
            requestSnapshot(application)
            publish()
        }
    }

    private fun requestSnapshot(context: Context) {
        runCatching { NativeRuleProtocol.send(context, Intent(NativeRuleProtocol.REQUEST).setPackage(NativeRuleProtocol.MODULE)) }
            .onFailure { DebugLog.w("NativeRules", "native settings resync request failed", it) }
    }

    fun publish() {
        handler.post {
            if (closed) return@post
            val target = context ?: run { reportWaiting("application context"); return@post }
            Preferences.nativeRuleSnapshot()?.let(ledger::offer)
            val snapshot = ledger.latest() ?: run { reportWaiting("authoritative settings snapshot"); return@post }
            waiting = null
            revision = SystemClock.elapsedRealtimeNanos().coerceAtLeast(revision + 1L)
            val current = revision
            handler.removeCallbacksAndMessages(token)
            // Finite replays cover native binding/AOT preparation racing cold SystemUI
            // startup. No heartbeat, input poll, or unconditional repeating timer.
            for (delay in NativeRuleStateSnapshot.REPLAY_DELAYS_MS) {
                handler.postAtTime({
                    if (closed || context !== target || revision != current) return@postAtTime
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
            NativeRuleProtocol.send(context, intent)
            if (forwarded != snapshot) {
                forwarded = snapshot
                DebugLog.i("NativeRules", "native settings forwarded commit=${snapshot.revision} epoch=${snapshot.epoch} columns=${snapshot.columns} hidden=${snapshot.hidden}")
            }
            DebugLog.d("NativeRules", "published native settings revision=$revision columns=${snapshot.columns} hidden=${snapshot.hidden} commit=${snapshot.revision}")
        }.onFailure { DebugLog.w("NativeRules", "native settings broadcast failed", it) }
    }

    private fun reportWaiting(reason: String) {
        if (waiting == reason) return
        waiting = reason
        DebugLog.w("NativeRules", "native settings publication waiting for $reason")
    }

    private fun detachContext() {
        val old = context
        context = null
        handler.removeCallbacksAndMessages(token)
        if (old != null && receiverRegistered) runCatching { old.unregisterReceiver(receiver) }
            .onFailure { DebugLog.w("NativeRules", "native settings observer cleanup failed", it) }
        receiverRegistered = false
    }

    fun close() {
        // Retire queued attachment/publication too, before an old API-102 class
        // generation can register another receiver after hot reload preparation.
        closed = true
        handler.removeCallbacksAndMessages(null)
        handler.post { detachContext() }
    }
}
