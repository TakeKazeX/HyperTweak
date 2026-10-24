package com.takekazex.hypertweak.hook.base.dex

import org.junit.Assert.*
import org.junit.Test

class DexControlFlowTest {
    @Test fun `edge dominance rejects exception bypass loops and unreachable targets`() {
        val graph = mapOf(0 to listOf(1, 9), 1 to listOf(2), 2 to listOf(1, 3), 3 to emptyList(), 9 to emptyList())
        assertTrue(DexControlFlow.edgeDominates(graph, setOf(0), 0, 1, 3))
        assertFalse(DexControlFlow.edgeDominates(graph, setOf(0, 2), 0, 1, 3))
        assertFalse(DexControlFlow.edgeDominates(graph, setOf(0), 0, 1, 99))
        assertEquals(setOf(1, 2, 3), DexControlFlow.reachable(graph, setOf(1)))
        assertFalse(DexControlFlow.edgeDominates(graph + (9 to listOf(3)), setOf(0), 0, 1, 3))
    }
}
