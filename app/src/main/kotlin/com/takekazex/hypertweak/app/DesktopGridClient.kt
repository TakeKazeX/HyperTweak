package com.takekazex.hypertweak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.hook.runtime.settings.SettingsTransport
import com.takekazex.hypertweak.settings.contract.DesktopGridStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** One bounded, identity-checked status request. It does not change settings. */
internal object DesktopGridClient {
    private val sequence = AtomicLong()
    suspend fun query(context: Context): DesktopGridStatus? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val app = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            val nonce = sequence.updateAndGet { maxOf(it + 1, SystemClock.elapsedRealtimeNanos()) }
            var registered = false
            var finished = false
            lateinit var receiver: BroadcastReceiver
            val timeout = Runnable { if (!finished) { finished = true; if (registered) runCatching { app.unregisterReceiver(receiver) }; continuation.resume(null) } }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (finished || intent.action != "com.takekazex.hypertweak.action.DESKTOP_GRID_STATE" ||
                        !SettingsTransport.trusted(context, sentFromPackage, sentFromUid, SettingsTransport.SYSTEM_UI) ||
                        intent.getLongExtra("nonce", 0) != nonce) return
                    val status = DesktopGridStatus(intent.getLongExtra("stage", -1).toInt(),
                        intent.getLongExtra("requested", -1).toInt(), intent.getLongExtra("observed", -1).toInt())
                    if (!status.valid()) return
                    finished = true
                    handler.removeCallbacks(timeout)
                    if (registered) runCatching { app.unregisterReceiver(this) }
                    continuation.resume(status)
                }
            }
            continuation.invokeOnCancellation { handler.post { if (!finished) { finished = true; handler.removeCallbacks(timeout); if (registered) runCatching { app.unregisterReceiver(receiver) } } } }
            runCatching {
                app.registerReceiver(receiver, IntentFilter("com.takekazex.hypertweak.action.DESKTOP_GRID_STATE"), Context.RECEIVER_EXPORTED)
                registered = true
                handler.postDelayed(timeout, 1500)
                SettingsTransport.send(app, Intent("com.takekazex.hypertweak.action.DESKTOP_GRID_QUERY")
                    .setPackage(SettingsTransport.SYSTEM_UI).putExtra("nonce", nonce))
            }.onFailure { if (!finished) { finished = true; handler.removeCallbacks(timeout); if (registered) runCatching { app.unregisterReceiver(receiver) }; continuation.resume(null) } }
        }
    }
}
