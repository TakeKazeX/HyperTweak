package com.takekazex.hypertweak.dock

import java.util.function.Consumer
import org.junit.Assert.*
import org.junit.Test

class DockWindowDiscoveryTest {
    private class Service(val mRoot: Root)
    private class Root(val existing: List<Any>) {
        var traversals = 0
        fun forAllWindows(consumer: Consumer<Any>, topToBottom: Boolean) {
            check(topToBottom)
            traversals++
            existing.forEach(consumer::accept)
        }
        fun forAllWindows(predicate: java.util.function.Predicate<Any>, topToBottom: Boolean): Boolean =
            topToBottom && existing.any(predicate::test)
    }
    @Test fun findsAlreadyExistingWindowsWithoutSavedStateOrNewPrepareCallback() {
        val home = Any(); val app = Any(); val root = Root(listOf(app, home))
        assertEquals(listOf(app, home), DockWindowDiscovery.collect(Service(root)))
        assertEquals(1, root.traversals)
    }
    @Test fun snapshotsAnEmptyTreeWithoutInventingAWindow() {
        assertTrue(DockWindowDiscovery.collect(Service(Root(emptyList()))).isEmpty())
    }
}
