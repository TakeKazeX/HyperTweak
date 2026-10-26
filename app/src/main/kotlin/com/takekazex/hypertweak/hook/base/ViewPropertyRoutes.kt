package com.takekazex.hypertweak.hook.base

/** Stable registration order, isolated failures, and the unmodified host request for every rule. */
internal class ViewPropertyRoutes<V : Any, T>(private val failure: (String, Throwable) -> Unit) {
    private class Route<V, T>(val owner: String, val type: Class<*>?, val before: ((V, T) -> T?)?, val after: ((V) -> Unit)?)
    private var routes = emptyList<Route<V, T>>()
    @Volatile private var dispatch = dispatchFor(routes)

    // A fresh ClassValue on membership changes gives each runtime type one classification pass.
    // No global Class-key map pins plugin loaders, and retired subscriptions leave no cached route.
    private fun dispatchFor(snapshot: List<Route<V, T>>) = object : ClassValue<List<Route<V, T>>>() {
        override fun computeValue(type: Class<*>): List<Route<V, T>> =
            snapshot.filter { it.type?.isAssignableFrom(type) != false }
    }

    @Synchronized fun register(owner: String, before: ((V, T) -> T?)?, after: ((V) -> Unit)?, type: Class<*>? = null): AutoCloseable {
        val route = Route(owner, type, before, after)
        routes = routes + route
        dispatch = dispatchFor(routes)
        return AutoCloseable { remove(route) }
    }
    @Synchronized private fun remove(route: Route<V, T>) { routes = routes - route; dispatch = dispatchFor(routes) }
    @Synchronized fun clear() { routes = emptyList(); dispatch = dispatchFor(routes) }

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
