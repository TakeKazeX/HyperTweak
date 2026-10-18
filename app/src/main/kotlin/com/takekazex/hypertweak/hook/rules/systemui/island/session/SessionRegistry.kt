package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import java.util.Collections
import java.util.WeakHashMap

/** Weak owner table shared by every binder; content ownership is resolved through the host. */
internal class SessionRegistry {
    private val sessions = Collections.synchronizedMap(WeakHashMap<Any, IslandSession>())

    operator fun get(owner: Any?): IslandSession? = owner?.let { sessions[it] }

    fun getOrCreate(owner: Any): IslandSession = synchronized(sessions) {
        sessions[owner] ?: IslandSession(owner).also { sessions[owner] = it }
    }

    fun forContent(host: IslandHostPorts, content: Any): IslandSession? {
        if (!host.isContent(content)) return null
        return host.ownerOf(content)?.let { sessions[it] }
    }

    fun owners(): List<Any> = synchronized(sessions) { sessions.keys.toList() }

    fun snapshot(): List<IslandSession> = synchronized(sessions) { sessions.values.toList() }

    fun remove(owner: Any) {
        sessions.remove(owner)
    }

    fun clear() = sessions.clear()
}
