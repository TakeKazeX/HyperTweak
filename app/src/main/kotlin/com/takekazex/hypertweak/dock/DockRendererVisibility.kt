package com.takekazex.hypertweak.dock

/** Publishes owned, active renderer UIDs without taking WMS's lock on freezer callbacks. */
internal class DockRendererVisibility {
    private data class Renderer(val uid: Int, val visible: Boolean)
    private val renderers = mutableMapOf<String, Renderer>()
    private var retired = false
    @Volatile private var visibleUids: Set<Int> = emptySet()

    @Synchronized fun update(id: String, uid: Int, visible: Boolean) {
        if (retired) return
        val previous = renderers[id]
        if (previous?.uid == uid && previous.visible == visible) return
        if (uid < 10_000) renderers.remove(id) else renderers[id] = Renderer(uid, visible)
        publish()
    }

    @Synchronized fun remove(id: String) {
        renderers.remove(id)
        publish()
    }

    @Synchronized fun close() {
        retired = true
        renderers.clear()
        publish()
    }

    fun protects(uid: Int) = uid in visibleUids

    private fun publish() {
        visibleUids = renderers.values.filter { it.visible }.mapTo(mutableSetOf()) { it.uid }
    }
}
