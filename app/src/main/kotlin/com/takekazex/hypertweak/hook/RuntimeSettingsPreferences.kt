package com.takekazex.hypertweak.hook

import android.content.SharedPreferences
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.CopyOnWriteArraySet

/** Read-only compatibility facade for vendored runtimes; callbacks come from the common dispatcher. */
internal object RuntimeSettingsPreferences : SharedPreferences {
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    override fun getAll(): MutableMap<String, *> = (Preferences.runtimePreferenceSource()?.all.orEmpty().filterKeys { !RuntimeSettingsStore.owns(it) } +
        RuntimeSettingsStore.values().orEmpty()).toMutableMap()
    override fun contains(key: String?): Boolean = key != null && Preferences.contains(key)
    override fun getBoolean(key: String?, defValue: Boolean) = key?.let { Preferences.getBoolean(it, defValue) } ?: defValue
    override fun getInt(key: String?, defValue: Int) = key?.let { Preferences.getInt(it, defValue) } ?: defValue
    override fun getFloat(key: String?, defValue: Float) = key?.let { Preferences.getFloat(it, defValue) } ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = when {
        key == null -> defValue
        RuntimeSettingsStore.owns(key) -> RuntimeSettingsStore.value(key) as? Long ?: defValue
        else -> Preferences.runtimePreferenceSource()?.getLong(key, defValue) ?: defValue
    }
    override fun getString(key: String?, defValue: String?): String? =
        if (key == null || !contains(key)) defValue else Preferences.getString(key, defValue.orEmpty())
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (key == null || !contains(key)) defValues?.toMutableSet() else Preferences.getStringSet(key).toMutableSet()
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
