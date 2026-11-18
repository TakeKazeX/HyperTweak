package com.takekazex.hypertweak.hook.base

/** Stable registration order, isolated failures, and the unmodified host request for every rule. */
internal class ViewPropertyRoutes<V : Any, T>(private val failure: (String, Throwable) -> Unit) {
    private class Route<V, T>(val owner: String, val type: Class<*>?, val before: ((V, T) -> T?)?, val after: ((V) -> Unit)?)
    private var routes = emptyList<Route<V, T>>()
    // False from clear() (retirement) until the next register(). A retiring generation's View hooks
    // keep firing until the replacement swaps the handles; a dispatch they read in that window, or
    // one published by a subscription closed after clear(), must not cache into host View classes.
    private var caching = true
    @Volatile private var dispatch = dispatchFor(routes)

    // A fresh class value on membership changes gives each runtime type one classification pass.
    // No global Class-key map pins plugin loaders. The replaced dispatch is retired, so neither old
    // subscriptions nor this module generation stay cached in host View classes.
    private fun dispatchFor(snapshot: List<Route<V, T>>) = RetiringClassValue { type ->
        snapshot.filter { it.type?.isAssignableFrom(type) != false }
    }.also { if (!caching) it.retire() }

    @Synchronized fun register(owner: String, before: ((V, T) -> T?)?, after: ((V) -> Unit)?, type: Class<*>? = null): AutoCloseable {
        val route = Route(owner, type, before, after)
        caching = true
        publish(routes + route)
        return AutoCloseable { remove(route) }
    }
    @Synchronized private fun remove(route: Route<V, T>) = publish(routes - route)
    /** Retirement: drops every route and caches nothing in host classes until the next [register]. */
    @Synchronized fun clear() {
        caching = false
        publish(emptyList())
    }

    private fun publish(next: List<Route<V, T>>) {
        val retired = dispatch
        routes = next
        dispatch = dispatchFor(next)
        retired.retire()
    }

    /** Non-null proposals compose in registration order; observers always receive the original. */
    fun before(view: V, requested: T): T {
        var result = requested
        dispatch.get(view.javaClass).forEach { route ->
            try { route.before?.invoke(view, requested)?.let { result = it } }
            catch (error: Throwable) { failure(route.owner, error) }
        }
        return result
    }
    fun after(view: V) {
        dispatch.get(view.javaClass).forEach { route ->
            try { route.after?.invoke(view) }
            catch (error: Throwable) { failure(route.owner, error) }
        }
    }
}
