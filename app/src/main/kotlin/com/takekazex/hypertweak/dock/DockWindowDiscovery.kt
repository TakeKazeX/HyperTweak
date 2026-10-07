package com.takekazex.hypertweak.dock

import java.util.function.Consumer

/** Snapshot the platform window tree before callbacks can change its ownership. Caller holds WMS lock. */
internal object DockWindowDiscovery {
    fun collect(service: Any): List<Any> {
        val root = requireNotNull(DockReflection.field(service, "mRoot").get(service))
        val windows = ArrayList<Any>()
        DockReflection.call(root, "forAllWindows", Consumer<Any> { windows.add(it) }, true)
        return windows
    }
}
