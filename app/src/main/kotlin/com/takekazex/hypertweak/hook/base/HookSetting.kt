package com.takekazex.hypertweak.hook.base

import java.lang.ref.WeakReference
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/** The explicit default is available even if the first storage read fails. */
internal class HookSetting<T>(default: T, private val read: () -> T) : ReadOnlyProperty<Any?, T> {
    @Volatile private var value: T = default
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
    // Serialize publication per slot, without holding the registry lock during storage access.
    @Synchronized fun refresh() { value = read() }
}

/** Weak registrations never retain a retired feature, plugin or module loader. */
internal class HookSettingRegistry(private val failure: (Throwable) -> Unit = {}) {
    private val settings = mutableListOf<WeakReference<HookSetting<*>>>()
    fun <T> capture(default: T, read: () -> T): HookSetting<T> {
        val setting = HookSetting(default, read)
        synchronized(settings) { settings += WeakReference(setting) }
        refreshOne(setting, failure)
        return setting
    }

    fun refresh(failure: (Throwable) -> Unit = this.failure) {
        val snapshot = synchronized(settings) {
            settings.removeAll { it.get() == null }
            settings.mapNotNull { it.get() }
        }
        snapshot.forEach { refreshOne(it, failure) }
    }

    private fun refreshOne(setting: HookSetting<*>, failure: (Throwable) -> Unit) {
        try { setting.refresh() } catch (error: Throwable) { runCatching { failure(error) } }
    }
}
