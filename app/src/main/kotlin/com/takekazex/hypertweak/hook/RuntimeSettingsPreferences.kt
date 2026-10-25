package com.takekazex.hypertweak.hook

import android.content.SharedPreferences
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.CopyOnWriteArraySet

/** Read-only compatibility facade for vendored runtimes; callbacks come from the common dispatcher. */
internal object RuntimeSettingsPreferences : SharedPreferences {
    interface Reader {
        fun source(): SharedPreferences?
        fun contains(key: String): Boolean
        fun getBoolean(key: String, default: Boolean): Boolean
        fun getInt(key: String, default: Int): Int
        fun getFloat(key: String, default: Float): Float
        fun getString(key: String, default: String): String
        fun getStringSet(key: String): Set<String>
    }
    @Volatile private var reader: Reader? = null
    fun bind(reader: Reader): SharedPreferences {
        this.reader = reader
        return this
    }
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    override fun getAll(): MutableMap<String, *> = (reader?.source()?.all.orEmpty().filterKeys { !RuntimeSettingsStore.owns(it) } +
        RuntimeSettingsStore.values().orEmpty()).toMutableMap()
    override fun contains(key: String?): Boolean = key != null && (reader?.contains(key) == true)
    override fun getBoolean(key: String?, defValue: Boolean) = key?.let { (reader?.getBoolean(it, defValue) ?: defValue) } ?: defValue
    override fun getInt(key: String?, defValue: Int) = key?.let { (reader?.getInt(it, defValue) ?: defValue) } ?: defValue
    override fun getFloat(key: String?, defValue: Float) = key?.let { (reader?.getFloat(it, defValue) ?: defValue) } ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = when {
        key == null -> defValue
        RuntimeSettingsStore.owns(key) -> RuntimeSettingsStore.value(key) as? Long ?: defValue
        else -> reader?.source()?.getLong(key, defValue) ?: defValue
    }
    override fun getString(key: String?, defValue: String?): String? =
        if (key == null || !contains(key)) defValue else reader?.getString(key, defValue.orEmpty())
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (key == null || !contains(key)) defValues?.toMutableSet() else reader?.getStringSet(key)?.toMutableSet()
    override fun edit(): SharedPreferences.Editor = error("Injected runtime settings are read-only")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += listener }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= listener }
    fun dispatch(groups: Set<String>) {
        RuntimeSettingsGroups.backKeys.filter { RuntimeSettingsGroups.affected(it).any(groups::contains) }.forEach { key ->
            listeners.forEach { listener -> runCatching { listener.onSharedPreferenceChanged(this, key) }
                .onFailure { DebugLog.w("RuntimeSettings", "runtime preference observer failed key=$key", it) } }
        }
    }
    fun clearObservers() { listeners.clear() }
}
