package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class TypeOnlyBranchPolicyTest {
    private class Node(vararg children: Node) {
        var parent: Node? = null
        val children = children.toMutableList()
        init { children.forEach { it.parent = this } }
    }
    private fun hidden(root: Node, label: Node) =
        TypeOnlyBranchPolicy.hiddenBranches(root, label, { it.parent }, { it.children })

    @Test fun nativeAndWrappedRowsPreserveTheLabelPath() {
        val label = Node()
        val bars = Node()
        val hd = Node()
        val group = Node(label, bars, hd)
        assertEquals(setOf(bars, hd), hidden(group, label))
        val wrapper = Node(group)
        val root = Node(wrapper)
        assertEquals(setOf(bars, hd), hidden(root, label))
    }

    @Test fun movingLabelOutsideSignalGroupMasksTheWholeOldBranch() {
        val label = Node()
        val signalGroup = Node(Node(), Node())
        val labelWrapper = Node(label, Node())
        val root = Node(signalGroup, labelWrapper)
        assertEquals(setOf(signalGroup, labelWrapper.children[1]), hidden(root, label))
        assertFalse(hidden(root, label)!!.contains(labelWrapper))
        assertFalse(hidden(root, label)!!.contains(label))
    }

    @Test fun unrelatedOrRemovedLabelDoesNotProduceAMask() {
        assertNull(hidden(Node(Node()), Node()))
    }
}
