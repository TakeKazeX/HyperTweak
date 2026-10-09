package com.takekazex.hypertweak.util

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Requests from different pages share the same operation for every overlapping package. */
internal class RestartFlights {
    private val mutableRunning = MutableStateFlow<Set<String>>(emptySet())
    val running = mutableRunning.asStateFlow()
    private val active = mutableMapOf<String, Deferred<Boolean>>()

    @Synchronized
    fun getOrStart(packageName: String, start: () -> Deferred<Boolean>): Deferred<Boolean> {
        active[packageName]?.let { return it }
        val flight = start()
        active[packageName] = flight
        mutableRunning.value = active.keys.toSet()
        flight.invokeOnCompletion {
            synchronized(this) {
                if (active[packageName] === flight) {
                    active.remove(packageName)
                    mutableRunning.value = active.keys.toSet()
                }
            }
        }
        return flight
    }
}
