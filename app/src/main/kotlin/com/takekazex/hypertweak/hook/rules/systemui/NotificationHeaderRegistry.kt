package com.takekazex.hypertweak.hook.rules.systemui

internal object NotificationHeaderRegistry {
    /** Living shareable notification header roots recorded by the header hookers. */
    private val headerRoots = java.util.WeakHashMap<android.view.ViewGroup, Boolean>()

    fun noteHeaderRoot(root: android.view.ViewGroup) {
        synchronized(headerRoots) { headerRoots[root] = true }
    }

    fun headerRoots(): List<android.view.ViewGroup> = synchronized(headerRoots) { headerRoots.keys.toList() }
}
