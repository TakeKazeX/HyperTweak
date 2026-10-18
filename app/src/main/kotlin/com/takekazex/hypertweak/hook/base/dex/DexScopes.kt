package com.takekazex.hypertweak.hook.base.dex

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Dispatch-owned detached indexes and backend lifetime; independent threads never borrow objects. */
internal class DexScopes(private val openBackend: (String) -> AutoCloseable) {
    private class Scope(val apk: String, val backend: AutoCloseable) {
        val artifacts = HashMap<Class<*>, Pair<String, Any>>()
    }
    private val scopes = ThreadLocal.withInitial { ArrayList<Scope>() }
    // Bootstrap collection/string keys plus weak Class keys avoid retaining a retired module
    // loader through a host thread's ThreadLocalMap. Values never pin a parsed index after dispatch.
    private val recent = ThreadLocal.withInitial { WeakHashMap<Class<*>, MutableMap<String, WeakReference<Any>>>() }
    private fun stack() = requireNotNull(scopes.get())

    fun begin(apk: String): AutoCloseable {
        val stack = stack()
        if (stack.any { it.apk == apk }) return AutoCloseable { }
        val scope = Scope(apk, openBackend(apk))
        stack.add(scope)
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                stack.remove(scope)
                scope.artifacts.clear()
                scope.backend.close()
            }
        }
    }

    fun <T : Any> artifact(apk: String, type: Class<T>, version: String, create: () -> T): T {
        val scope = stack().lastOrNull { it.apk == apk }
        scope?.artifacts?.get(type)?.takeIf { it.first == version }?.let { return type.cast(it.second) }
        val versions = requireNotNull(recent.get()).getOrPut(type) { HashMap() }
        val value = versions[version]?.get()?.let(type::cast) ?: create()
        versions.clear()
        versions[version] = WeakReference(value)
        scope?.artifacts?.put(type, version to value)
        return value
    }
}
