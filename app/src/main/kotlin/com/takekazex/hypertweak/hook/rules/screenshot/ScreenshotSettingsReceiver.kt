package com.takekazex.hypertweak.hook.rules.screenshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.NativeRuleProtocol
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog

/** Each executing screenshot process owns its receiver and retires it with its hook generation. */
internal class ScreenshotSettingsReceiver {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    @Volatile private var closed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || intent.action != ScreenshotSettingsProtocol.CHANGED ||
                !NativeRuleProtocol.trusted(context, sentFromPackage, sentFromUid, NativeRuleProtocol.MODULE)) return
            val snapshot = ScreenshotSettingsProtocol.decode(intent) ?: return
            if (Preferences.acceptScreenshotSettings(snapshot)) {
                DebugLog.i("ScreenshotAutoFrame", "screenshot settings applied revision=${snapshot.revision} enabled=${snapshot.enabled} mode=${snapshot.editorMode}")
            }
        }
    }
    @Synchronized fun attach(host: Context) {
        if (closed || context != null) return
        val app = host.applicationContext ?: host
        if (app.packageName != ScreenshotSettingsProtocol.PACKAGE) return
        runCatching {
            app.registerReceiver(receiver, IntentFilter(ScreenshotSettingsProtocol.CHANGED), Context.RECEIVER_EXPORTED)
            context = app
            DebugLog.i("ScreenshotAutoFrame", "screenshot settings receiver ready")
            listOf(0L, 250L, 1000L).forEach { delay -> main.postDelayed({
                if (!closed) runCatching { NativeRuleProtocol.send(app,
                    Intent(ScreenshotSettingsProtocol.REQUEST).setPackage(NativeRuleProtocol.MODULE)) }
                    .onFailure { DebugLog.w("ScreenshotAutoFrame", "screenshot settings resync failed", it) }
            }, delay) }
        }.onFailure { DebugLog.w("ScreenshotAutoFrame", "screenshot settings receiver registration failed", it) }
    }
    @Synchronized fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        context?.let { app -> runCatching { app.unregisterReceiver(receiver) }
            .onFailure { DebugLog.w("ScreenshotAutoFrame", "screenshot settings receiver cleanup failed", it) } }
        context = null
    }
}
