package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.hook.preferences.putPreferenceValue
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups

/** Publish only the last acknowledged snapshot; rejected editor shadows never become live state. */
internal object RuntimeSettingsSignal {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var context: Context? = null
    private var source: SharedPreferences? = null
    private var acknowledged: RuntimeSettingsSnapshot? = null
    fun attach(context: Context) { this.context = context.applicationContext ?: context }
    @Synchronized fun bind(prefs: SharedPreferences) {
        if (source === prefs) return
        val values = prefs.all
        val stored = (values[RuntimeSettingsGroups.REVISION] as? Long) ?: 0
        if (acknowledged != null) {
            source = prefs
            publishCommitted()
            return
        }
        val next = RuntimeSettingsSnapshot.read(maxOf(SystemClock.elapsedRealtimeNanos(), stored), values) ?: run {
            DebugLog.w("RuntimeSettings", "invalid runtime settings baseline; snapshot not published")
            return
        }
        source = prefs
        acknowledged = next
        publishCommitted()
    }
    @Synchronized fun prepare(key: String, value: Any): RuntimeSettingsSnapshot = prepare(mapOf(key to value))
    @Synchronized fun prepare(changes: Map<String, Any>): RuntimeSettingsSnapshot {
        val old = acknowledged ?: error("runtime settings baseline unavailable")
        require(changes.keys.all(RuntimeSettingsSnapshot::owns))
        return RuntimeSettingsSnapshot.read(maxOf(old.revision + 1, SystemClock.elapsedRealtimeNanos()), old.values + changes)
            ?: error("invalid runtime settings")
    }
    @Synchronized fun lastAcknowledged(): RuntimeSettingsSnapshot? = acknowledged
    @Synchronized fun acknowledge(snapshot: RuntimeSettingsSnapshot) {
        if (snapshot.revision <= (acknowledged?.revision ?: 0)) return
        acknowledged = snapshot
        publishCommitted()
    }
    /** Ordinary commits send a delta while repairing rejected editor shadows. A clear/replacement
     * must explicitly repopulate the entire desired subset, even values equal to the baseline. */
    @Synchronized fun write(editor: SharedPreferences.Editor, snapshot: RuntimeSettingsSnapshot,
        complete: Boolean = false) {
        val plan = RuntimeSettingsWritePlan.create(source?.all.orEmpty(),
            acknowledged?.values.orEmpty(), snapshot.values, complete)
        plan.remove.forEach(editor::remove)
        editor.putLong(RuntimeSettingsGroups.REVISION, snapshot.revision)
        plan.put.forEach { (key, value) -> editor.putPreferenceValue(key, value) }
    }
    @Synchronized fun prepareReplacement(values: Map<String, *>): RuntimeSettingsSnapshot =
        requireNotNull(RuntimeSettingsSnapshot.read(maxOf((acknowledged?.revision ?: 0) + 1,
            SystemClock.elapsedRealtimeNanos()), values)) { "invalid runtime settings replacement" }
    fun publishCommitted(targetPackage: String? = null) {
        main.post {
            val target = context ?: return@post
            val snapshot = synchronized(this) { acknowledged } ?: return@post
            val destinations = targetPackage?.let { setOf(it).intersect(RuntimeSettingsGroups.targets) } ?: RuntimeSettingsGroups.targets
            destinations.forEach { destination ->
                runCatching { SettingsTransport.send(target, RuntimeSettingsProtocol.encode(snapshot, destination)) }
                    .onSuccess { DebugLog.i("RuntimeSettings", "snapshot sent target=$destination revision=${snapshot.revision}") }
                    .onFailure { DebugLog.w("RuntimeSettings", "snapshot send failed target=$destination", it) }
            }
        }
    }
}
