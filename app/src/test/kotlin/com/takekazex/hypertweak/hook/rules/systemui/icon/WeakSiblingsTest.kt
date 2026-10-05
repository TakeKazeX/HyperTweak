package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import org.junit.Assert.*
import org.junit.Test

class WeakSiblingsTest {
    private class Tree { val children = mutableListOf<Child>() }
    private class Child(val parent: Tree)

    private fun install(map: WeakHashMap<Child, WeakSiblings<Child>>): List<WeakReference<Any>> {
        val tree = Tree()
        val owner = Child(tree)
        val status = Child(tree)
        val fake = Child(tree)
        tree.children.addAll(listOf(owner, status, fake))
        map[owner] = WeakSiblings(status, fake)
        assertSame(status, map[owner]?.first?.get())
        return listOf(WeakReference(tree), WeakReference(owner), WeakReference(status), WeakReference(fake))
    }

    @Test fun commonParentAndEverySiblingAreCollectableWhileModuleMapLives() {
        val map = WeakHashMap<Child, WeakSiblings<Child>>()
        val references = install(map)
        repeat(100) {
            if (references.all { it.get() == null }) return@repeat
            System.gc()
            Thread.sleep(10)
        }
        assertTrue("old tree retained by module cache", references.all { it.get() == null })
        assertTrue(map.isEmpty())
    }
}
