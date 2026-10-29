package com.takekazex.hypertweak.hook.nativebridge

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.runtime.settings.SettingsTransport
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.takekazex.hypertweak.util.logging.DebugLog

/** SystemUI owns remote Preferences; HYOS receives a complete, identity-sharing snapshot. */
internal class NativeRuleStatePublisher {
    private val handler = Handler(Looper.getMainLooper())
    private val token = Any()
    private var context: Context? = null
    private var dockPort = 0
    private var dockToken0 = 0L
    private var dockToken1 = 0L
    private var dockOfferVersion = 0L
    private var dockEndpointKnown = false
    private var revision = 0L
    private var receiverRegistered = false
    private val ledger = NativeSnapshotLedger()
    private var recentsHaptics = RecentsCardHaptics()
    private var iconSize: DesktopIconSizeController? = null
    private var waiting: String? = null
    private var forwarded: NativeRuleStateSnapshot? = null
    @Volatile private var closed = false
    fun saveSnapshot(): Array<Any> = arrayOf(ledger.save() ?: emptyArray<Any>(),
        Preferences.saveBackGestureSettings() ?: emptyArray<Any>())
    fun restoreSnapshot(state: Any?) {
        val values = state as? Array<*>
        if (values != null && values.size >= 2 && values[0] is Array<*>) {
            ledger.restore(values[0])
            Preferences.restoreBackGestureSettings(values[1])
        } else ledger.restore(state)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == "com.takekazex.hypertweak.action.NATIVE_RULE_PREPARED") {
                if (SettingsTransport.trusted(context, sentFromPackage, sentFromUid, "com.miui.home")) publish()
                return
            }
            if (intent.action == RecentsCardHaptics.ACTION) {
                if (SettingsTransport.trusted(context, sentFromPackage, sentFromUid, "com.miui.home") &&
                    ledger.latest()?.recentsHaptic == true) recentsHaptics.play(context,
                        intent.getLongExtra(RecentsCardHaptics.EVENT_TIME, 0L))
                return
            }
            if (intent.action == com.takekazex.hypertweak.dock.DockFrameChannel.OFFER) {
                if (!SettingsTransport.trusted(context, sentFromPackage, sentFromUid, "android") || sentFromUid != Process.SYSTEM_UID) return
                val port = intent.getIntExtra("port", -1)
                val t0 = intent.getLongExtra("token0", 0)
                val t1 = intent.getLongExtra("token1", 0)
                val version = intent.getLongExtra("version", 0)
                if (version <= dockOfferVersion || port !in 0..65535 || (port != 0 && (t0 == 0L || t1 == 0L))) return
                dockEndpointKnown = true
                dockOfferVersion = version
                dockPort = port; dockToken0 = t0; dockToken1 = t1
                publish() // The same endpoint offer also rebinds a newly started launcher process.
                return
            }
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_USER_UNLOCKED || intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
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
            recentsHaptics = RecentsCardHaptics()
            this.context = application
            iconSize = DesktopIconSizeController(application)
            runCatching {
                application.registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                    addAction(RecentsCardHaptics.ACTION)
                    addAction("com.takekazex.hypertweak.action.NATIVE_RULE_PREPARED")
                    addAction(com.takekazex.hypertweak.dock.DockFrameChannel.OFFER)
                }, Context.RECEIVER_EXPORTED)
                receiverRegistered = true
            }.onFailure { DebugLog.w("NativeRules", "boot settings publisher registration failed", it) }
            DebugLog.i("NativeRules", "SystemUI native settings publisher attached observer=$receiverRegistered")
            runCatching { SettingsTransport.send(application,
                Intent(com.takekazex.hypertweak.dock.DockFrameChannel.REQUEST).setPackage("android")) }
                .onFailure { DebugLog.w("DockMotion", "channel resync request failed", it) }
            publish()
        }
    }

    fun publish() {
        handler.post {
            if (closed) return@post
            val target = context ?: run { reportWaiting("application context"); return@post }
            Preferences.nativeRuleSnapshot()?.let(ledger::offer)
            val snapshot = ledger.latest() ?: run { reportWaiting("authoritative settings snapshot"); return@post }
            waiting = null
            if (snapshot.recentsHaptic) recentsHaptics.prepare(target) else recentsHaptics.reset()
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
        iconSize?.submit(snapshot.iconPercent)
        runCatching {
            val intent = Intent("com.android.systemui.fsgesture").setPackage("com.miui.home")
                // Schema 3 stays readable by a launcher native payload that predates this
                // switch; the new boolean is an optional, namespaced extension. A launcher
                // refreshed after this build reads it and enables card haptics, while an
                // older payload still accepts the rest of the snapshot instead of rejecting
                // the entire settings carrier.
                .putExtra("hypertweak_rule_schema", 3)
                .putExtra("hypertweak_rule_revision", revision)
                .putExtra("hypertweak_rule_hide_clear", snapshot.hidden)
                .putExtra("hypertweak_rule_folder_columns", snapshot.columns)
                .putExtra("hypertweak_rule_contextual_search", snapshot.contextualSearch)
                .putExtra("hypertweak_rule_assistant_widgets", snapshot.assistantWidgets)
                .putExtra("hypertweak_rule_recents_haptic", snapshot.recentsHaptic)
                .putExtra("hypertweak_rule_folder_stack", snapshot.folderStack)
                .putExtra("hypertweak_rule_widget_stack", snapshot.widgetStack)
                .putExtra("hypertweak_rule_xiaoai_options", snapshot.xiaoaiOptions)
                .putExtra("hypertweak_rule_icon_percent", snapshot.iconPercent)
                .putExtra("sender_uid", Process.myUid())
            if (dockEndpointKnown) intent.putExtra("hypertweak_rule_dock_channel", true)
                .putExtra("hypertweak_rule_dock_port", dockPort)
                .putExtra("hypertweak_rule_dock_token0", dockToken0)
                .putExtra("hypertweak_rule_dock_token1", dockToken1)
            SettingsTransport.send(context, intent)
            if (forwarded != snapshot) {
                forwarded = snapshot
                DebugLog.i("NativeRules", "native settings forwarded commit=${snapshot.revision} epoch=${snapshot.epoch} columns=${snapshot.columns} hidden=${snapshot.hidden} widgets=${snapshot.assistantWidgets}")
            }
            DebugLog.d("NativeRules", "published native settings revision=$revision columns=${snapshot.columns} hidden=${snapshot.hidden} widgets=${snapshot.assistantWidgets} commit=${snapshot.revision}")
        }.onFailure { DebugLog.w("NativeRules", "native settings broadcast failed", it) }
    }

    private fun reportWaiting(reason: String) {
        if (waiting == reason) return
        waiting = reason
        DebugLog.w("NativeRules", "native settings publication waiting for $reason")
    }

    private fun detachContext() {
        recentsHaptics.close()
        iconSize?.close(); iconSize = null
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
