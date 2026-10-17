package com.takekazex.hypertweak.hook.rules.screenshot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.hook.NativeRuleProtocol
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog

internal object ScreenshotSettingsProtocol {
    const val PACKAGE = "com.miui.screenshot"
    const val CHANGED = "${NativeRuleProtocol.MODULE}.action.SCREENSHOT_SETTINGS_CHANGED"
    const val REQUEST = "${NativeRuleProtocol.MODULE}.action.SCREENSHOT_SETTINGS_REQUEST"
    fun encode(snapshot: ScreenshotSettingsSnapshot) = Intent(CHANGED).setPackage(PACKAGE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        .putExtra("schema", 1).putExtra("revision", snapshot.revision)
        .putExtra("enabled", snapshot.enabled).putExtra("mode", snapshot.editorMode)
    fun decode(intent: Intent): ScreenshotSettingsSnapshot? = runCatching {
        if (intent.getIntExtra("schema", -1) != 1) return null
        val extras = intent.extras ?: return null
        @Suppress("DEPRECATION")
        val revision = extras.get("revision") as? Long ?: return null
        @Suppress("DEPRECATION")
        val enabled = extras.get("enabled") as? Boolean ?: return null
        @Suppress("DEPRECATION")
        val mode = extras.get("mode") as? Int ?: return null
        ScreenshotSettingsSnapshot.read(revision, mapOf(Preferences.KEY_SCREENSHOT_AUTO_FRAME to enabled,
            Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE to mode))
    }.getOrNull()
}

/** Publish the complete committed subset; a rejected editor shadow is never a live setting. */
// This publisher retains only the module application context for its process lifetime.
@SuppressLint("StaticFieldLeak")
internal object ScreenshotSettingsSignal {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var context: Context? = null
    private var source: SharedPreferences? = null
    private val acknowledged = ScreenshotSettingsLedger()
    fun attach(context: Context) { this.context = context.applicationContext ?: context }
    private fun revision() = maxOf((acknowledged.latest()?.revision ?: 0) + 1, SystemClock.elapsedRealtimeNanos())
    @Synchronized fun bind(prefs: SharedPreferences) {
        if (source === prefs) return
        val values = prefs.all
        val known = acknowledged.latest()
        val storedRevision = (values[ScreenshotSettingsSnapshot.REVISION] as? Long) ?: 0
        if (known != null && storedRevision <= known.revision) {
            // A service rebind can race an in-flight commit. Do not mint a newer identity
            // for the old remote map and invalidate the actual acknowledgement.
            source = prefs
            publishCommitted()
            return
        }
        val next = ScreenshotSettingsSnapshot.read(maxOf(revision(), storedRevision), values) ?: run {
            DebugLog.w("ScreenshotAutoFrame", "invalid screenshot settings baseline; snapshot not published")
            return
        }
        source = prefs
        acknowledged.accept(next)
        publishCommitted()
    }
    fun lastAcknowledged(): ScreenshotSettingsSnapshot? = acknowledged.latest()
    @Synchronized fun prepare(key: String, value: Any): ScreenshotSettingsSnapshot =
        requireNotNull(acknowledged.latest()?.mutate(key, value, revision())) { "No acknowledged screenshot settings baseline" }
    fun write(editor: SharedPreferences.Editor, snapshot: ScreenshotSettingsSnapshot) {
        editor.putBoolean(Preferences.KEY_SCREENSHOT_AUTO_FRAME, snapshot.enabled)
        editor.putInt(Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE, snapshot.editorMode)
        editor.putLong(ScreenshotSettingsSnapshot.REVISION, snapshot.revision)
    }
    @Synchronized fun acknowledge(snapshot: ScreenshotSettingsSnapshot) {
        if (acknowledged.accept(snapshot)) publishCommitted()
    }
    @Synchronized fun replaceCommitted(values: Map<String, *>) {
        val next = ScreenshotSettingsSnapshot.read(revision(), values) ?: return
        acknowledged.accept(next)
        publishCommitted()
    }
    fun publishCommitted() {
        main.post {
            val target = context ?: return@post
            val snapshot = acknowledged.latest() ?: return@post
            runCatching { NativeRuleProtocol.send(target, ScreenshotSettingsProtocol.encode(snapshot)) }
                .onSuccess { DebugLog.i("ScreenshotAutoFrame", "committed screenshot settings sent revision=${snapshot.revision} enabled=${snapshot.enabled} mode=${snapshot.editorMode}") }
                .onFailure { DebugLog.w("ScreenshotAutoFrame", "screenshot settings send failed", it) }
        }
    }
}
