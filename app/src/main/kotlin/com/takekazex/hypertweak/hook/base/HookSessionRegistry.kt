package com.takekazex.hypertweak.hook.base

import java.util.IdentityHashMap

/** Components using the same actual loader share one set of hooks until the last unload. */
internal class HookSessionRegistry<State : Any, Session : Any> {
    private class Shared<Session>(val session: Session, var references: Int = 0)
    private class Binding<State, Session>(val loader: Any, val state: State, val shared: Shared<Session>)
    private val loaders = IdentityHashMap<Any, Shared<Session>>()
    private val owners = IdentityHashMap<Any, Binding<State, Session>>()

    /** Factory constructs dormant hookers only; installation happens outside this short lock. */
    @Synchronized fun acquire(owner: Any, loader: Any, state: State, create: () -> Session): Pair<Session, Boolean> {
        owners[owner]?.let { return it.shared.session to false }
        var created = false
        val shared = loaders[loader] ?: Shared(create()).also { loaders[loader] = it; created = true }
        shared.references++
        owners[owner] = Binding(loader, state, shared)
        return shared.session to created
    }

    @Synchronized operator fun get(owner: Any?): Session? = owners[owner]?.shared?.session

    /** Only the final owner receives a session to tear down. */
    @Synchronized fun release(owner: Any): Session? {
        val binding = owners.remove(owner) ?: return null
        if (--binding.shared.references > 0) return null
        loaders.remove(binding.loader)
        return binding.shared.session
    }

    @Synchronized fun sessions(): List<Session> = loaders.values.map { it.session }
    @Synchronized fun states(): List<State> = owners.values.map { it.state }
    @Synchronized fun bindings(): List<Pair<Any, Session>> {
        val seen = IdentityHashMap<Session, Boolean>()
        return owners.mapNotNull { (owner, binding) ->
            val session = binding.shared.session
            if (seen.put(session, true) == null) owner to session else null
        }
    }
    @Synchronized fun clear() { owners.clear(); loaders.clear() }
}
