package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.hook.preferences.putPreferenceValue
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups

/** Publish only the last acknowledged snapshot; rejected editor shadows never become live state. */
internal object RuntimeSettingsSignal {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Always the application context, so the process-wide object retains no Activity or View. */
    @Volatile private var appContext: Context? = null
    private var source: SharedPreferences? = null
    private var acknowledged: RuntimeSettingsSnapshot? = null
    /**
     * Targets whose request arrived before the first baseline; `null` stands for every target.
     * A cold start caused by one host's request answers that host only: every running host
     * already holds the committed values, and re-broadcasting the unchanged baseline woke all
     * ~45 scoped packages on each module-process start.
     */
    private val deferred = LinkedHashSet<String?>()
    fun attach(context: Context) {
        appContext = context.applicationContext ?: context
        // Include deselected keyboards too, so disable/reset reaches an already running host.
        val packages = runCatching {
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.inputMethodList.orEmpty().map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        RuntimeSettingsGroups.registerImeTargets(packages)
    }
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
        val waiting = deferred.toList().also { deferred.clear() }
        // A commit whose broadcast never went out (process death between commit and publish)
        // is still repaired on the next start: only an already delivered revision is skipped.
        if (null in waiting || stored != publishedRevision()) publishCommitted(committed = stored)
        else waiting.forEach { publishCommitted(it) }
    }
    private fun ledger() = appContext?.getSharedPreferences(LEDGER, Context.MODE_PRIVATE)
    private fun publishedRevision(): Long = runCatching { ledger()?.getLong(PUBLISHED, -1L) }.getOrNull() ?: -1L
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
        publishCommitted(committed = snapshot.revision)
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
    /** [committed] is the stored revision a complete fan-out delivers; recorded once all sends succeed. */
    fun publishCommitted(targetPackage: String? = null, committed: Long? = null) {
        main.post {
            val target = appContext ?: return@post
            val snapshot = synchronized(this) { acknowledged ?: run { deferred += targetPackage; null } }
                ?: return@post
            RuntimeSettingsGroups.registerImeTargets(
                (snapshot.values[com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES]
                    as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty())
            val destinations = targetPackage?.let { setOf(it).intersect(RuntimeSettingsGroups.targets) } ?: RuntimeSettingsGroups.targets
            val failed = destinations.count { destination ->
                runCatching { SettingsTransport.send(target, RuntimeSettingsProtocol.encode(snapshot, destination)) }
                    .onSuccess { DebugLog.i("RuntimeSettings", "snapshot sent target=$destination revision=${snapshot.revision}") }
                    .onFailure { DebugLog.w("RuntimeSettings", "snapshot send failed target=$destination", it) }
                    .isFailure
            }
            if (targetPackage == null && committed != null && failed == 0) {
                runCatching { ledger()?.edit()?.putLong(PUBLISHED, committed)?.apply() }
            }
        }
    }
    private const val LEDGER = "runtime_settings_signal"
    private const val PUBLISHED = "published_revision"
}
