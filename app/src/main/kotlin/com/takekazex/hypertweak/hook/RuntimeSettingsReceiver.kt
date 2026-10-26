package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsProtocol
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsStore
import com.takekazex.hypertweak.hook.runtime.settings.SettingsTransport
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.util.logging.DebugLog

/** Process-owned settings transport, independent of native-rule publication and feature hooks. */
internal class RuntimeSettingsReceiver(private val changed: (Set<String>, Set<String>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    @Volatile private var retired = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (retired) return
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_USER_UNLOCKED) {
                request(context)
                return
            }
            if (intent.action != RuntimeSettingsProtocol.CHANGED ||
                !SettingsTransport.trusted(context, sentFromPackage, sentFromUid, SettingsTransport.MODULE)) return
            val snapshot = RuntimeSettingsProtocol.decode(intent) ?: return
            val groups = RuntimeSettingsStore.changedGroups(snapshot)
            // The participant needs the keys too: a shared domain can own several hooks and must
            // route each committed change to the hooker that applies it.
            val keys = RuntimeSettingsStore.changedKeys(snapshot)
            if (!RuntimeSettingsStore.accept(snapshot)) return
            DebugLog.i("RuntimeSettings", "snapshot received revision=${snapshot.revision} " +
                "groups=$groups keys=${keys.size} values=${snapshot.values.size}")
            changed(groups, keys)
        }
    }
    fun attach(host: Context) {
        val application = host.applicationContext ?: host
        main.post {
            if (retired || context === application) return@post
            detach()
            runCatching {
                application.registerReceiver(receiver, IntentFilter().apply {
                    addAction(RuntimeSettingsProtocol.CHANGED)
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                }, Context.RECEIVER_EXPORTED)
                context = application
                listOf(0L, 250L, 1000L).forEach { delay -> main.postDelayed({ if (!retired) request(application) }, delay) }
            }.onFailure { DebugLog.w("RuntimeSettings", "receiver registration failed", it) }
        }
    }
    private val resyncTask = Runnable { context?.let(::request) }
    fun resync() {
        main.post {
            if (!retired) {
                main.removeCallbacks(resyncTask)
                main.postDelayed(resyncTask, 150L)
            }
        }
    }
    private fun request(context: Context) {
        runCatching { SettingsTransport.send(context,
            Intent(RuntimeSettingsProtocol.REQUEST).setPackage(SettingsTransport.MODULE)) }
            .onFailure { DebugLog.w("RuntimeSettings", "snapshot resync request failed", it) }
    }
    private fun detach() {
        context?.let { old -> runCatching { old.unregisterReceiver(receiver) }
            .onFailure { DebugLog.w("RuntimeSettings", "receiver cleanup failed", it) } }
        context = null
    }
    fun close() {
        retired = true
        main.removeCallbacksAndMessages(null)
        main.post { detach() }
    }
}
