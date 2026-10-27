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
internal class RuntimeSettingsReceiver(
    private val changed: (Set<String>, Set<String>) -> Unit,
    private val upstreamPackageChanged: ((Context) -> Unit)? = null
) {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    @Volatile private var retired = false
    private var packageReceiverRegistered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (retired || intent.data?.schemeSpecificPart !=
                com.takekazex.hypertweak.util.platform.UpstreamBackGesture.PACKAGE) return
            // Replacement sends REMOVED before ADDED; never briefly reclaim during an upgrade.
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            refreshUpstream(context)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (retired) return
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_USER_UNLOCKED) {
                refreshUpstream(context)
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
            if (retired) return@post
            if (context === application) {
                attachPackageReceiver(application)
                return@post
            }
            detach()
            runCatching {
                application.registerReceiver(receiver, IntentFilter().apply {
                    addAction(RuntimeSettingsProtocol.CHANGED)
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                }, Context.RECEIVER_EXPORTED)
                context = application
                attachPackageReceiver(application)
                listOf(0L, 250L, 1000L).forEach { delay -> main.postDelayed({ if (!retired) request(application) }, delay) }
            }.onFailure { DebugLog.w("RuntimeSettings", "receiver registration failed", it) }
        }
    }
    private fun refreshUpstream(host: Context) {
        if (retired) return
        runCatching { upstreamPackageChanged?.invoke(host) }
            .onFailure { DebugLog.w("BackGesture", "upstream package refresh failed", it) }
    }
    private fun attachPackageReceiver(application: Context) {
        if (retired || upstreamPackageChanged == null || packageReceiverRegistered) return
        runCatching {
            application.registerReceiver(packageReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }, Context.RECEIVER_EXPORTED)
            packageReceiverRegistered = true
            // Repairs upgrades from generations which never observed package changes.
            refreshUpstream(application)
        }.onFailure { DebugLog.w("BackGesture", "package receiver registration failed", it) }
    }
    private val resyncTask = Runnable {
        context?.let { attachPackageReceiver(it); request(it) }
    }
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
        if (packageReceiverRegistered) {
            context?.let { old -> runCatching { old.unregisterReceiver(packageReceiver) }
                .onFailure { DebugLog.w("BackGesture", "package receiver cleanup failed", it) } }
            packageReceiverRegistered = false
        }
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
