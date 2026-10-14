package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.util.DebugLog

internal object IconSettingsProtocol {
    const val CHANGED = "com.takekazex.hypertweak.action.ICON_SETTINGS_CHANGED"
    const val REQUEST = "com.takekazex.hypertweak.action.ICON_SETTINGS_REQUEST"
    fun encode(snapshot: IconSettingsSnapshot): Intent {
        val values = Bundle()
        snapshot.values.forEach { (key, value) -> when (value) {
            is Boolean -> values.putBoolean(key, value)
            is Int -> values.putInt(key, value)
            is Long -> values.putLong(key, value)
            is Float -> values.putFloat(key, value)
            is String -> values.putString(key, value)
            is Set<*> -> values.putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
        } }
        return Intent(CHANGED).setPackage(NativeRuleProtocol.SYSTEM_UI)
            .putExtra("schema", 1).putExtra("revision", snapshot.revision).putExtra("values", values)
    }
    fun decode(intent: Intent): IconSettingsSnapshot? = runCatching {
        if (intent.getIntExtra("schema", -1) != 1) return null
        val values = intent.getBundleExtra("values") ?: return null
        @Suppress("DEPRECATION")
        val map = values.keySet().associateWith { key ->
            val value = values.get(key)
            if (value is ArrayList<*> && value.all { it is String }) value.toSet() else value
        }
        if (map.keys.any { !IconSettingsSnapshot.owns(it) }) return null
        IconSettingsSnapshot.read(intent.getLongExtra("revision", -1), map)
    }.getOrNull()
}

/** Publish only the last acknowledged snapshot; rejected editor shadows never become live state. */
internal object IconSettingsSignal {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var context: Context? = null
    private var source: SharedPreferences? = null
    private var acknowledged: IconSettingsSnapshot? = null
    fun attach(context: Context) { this.context = context.applicationContext ?: context }
    @Synchronized fun bind(prefs: SharedPreferences) {
        if (source === prefs) return
        val next = IconSettingsSnapshot.read(SystemClock.elapsedRealtimeNanos(), prefs.all) ?: run {
            DebugLog.w("IconTuner", "invalid icon settings baseline; snapshot not published")
            return
        }
        source = prefs
        acknowledged = next
        publishCommitted()
    }
    @Synchronized fun acknowledge(key: String, value: Any) {
        val old = acknowledged ?: return
        acknowledged = old.mutate(key, value, maxOf(old.revision + 1, SystemClock.elapsedRealtimeNanos())) ?: return
        publishCommitted()
    }
    @Synchronized fun replaceCommitted(values: Map<String, *>) {
        val revision = maxOf((acknowledged?.revision ?: 0) + 1, SystemClock.elapsedRealtimeNanos())
        acknowledged = IconSettingsSnapshot.read(revision, values) ?: return
        publishCommitted()
    }
    fun publishCommitted() {
        main.post {
            val target = context ?: return@post
            val snapshot = synchronized(this) { acknowledged } ?: return@post
            runCatching { NativeRuleProtocol.send(target, IconSettingsProtocol.encode(snapshot)) }
                .onSuccess { DebugLog.i("IconTuner", "committed icon snapshot sent revision=${snapshot.revision} keys=${snapshot.values.size}") }
                .onFailure { DebugLog.w("IconTuner", "icon snapshot send failed", it) }
        }
    }
}
