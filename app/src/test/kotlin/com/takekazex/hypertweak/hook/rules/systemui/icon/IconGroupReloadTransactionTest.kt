package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IconGroupReloadTransactionTest {
    @Test
    fun repeatedReloadNeverPublishesNewIndicesIntoOldGroups() {
        val groups = listOf("HOME", "KEYGUARD", "QS", "QS_FAKE")
        val attached = groups.toMutableSet()
        var order = 0
        repeat(5) {
            IconGroupReloadTransaction.run(
                groups, attached::contains,
                detach = { assertTrue(attached.remove(it)) },
                applyOrder = { assertTrue(attached.isEmpty()); order++ },
                restoreOrder = { fail("Unexpected rollback") },
                attach = { assertTrue(attached.add(it)) }
            )
            assertEquals(groups.toSet(), attached)
        }
        assertEquals(5, order)
    }

    @Test
    fun partialNativeAttachFailureRestoresEveryGroupAndTheOldOrder() {
        val groups = listOf("HOME", "KEYGUARD", "QS")
        val attached = groups.toMutableSet()
        var order = "native"
        var failNextQs = true
        try {
            IconGroupReloadTransaction.run(
                groups, attached::contains, attached::remove,
                applyOrder = { order = "custom" },
                restoreOrder = { assertTrue(attached.isEmpty()); order = "native" },
                attach = {
                    assertTrue(attached.add(it))
                    if (it == "QS" && failNextQs) {
                        failNextQs = false
                        error("Native callback failed after adding QS")
                    }
                }
            )
            fail("Expected native failure")
        } catch (expected: IllegalStateException) {
            assertEquals("Native callback failed after adding QS", expected.message)
        }
        assertEquals("native", order)
        assertEquals(groups.toSet(), attached)
    }

    @Test
    fun partialDetachFailureDoesNotLoseTheUnprocessedGroups() {
        val groups = listOf("HOME", "KEYGUARD", "QS")
        val attached = groups.toMutableSet()
        var failed = false
        try {
            IconGroupReloadTransaction.run(
                groups, attached::contains,
                detach = {
                    attached.remove(it)
                    if (it == "KEYGUARD" && !failed) { failed = true; error("detach") }
                },
                applyOrder = { fail("Must not reorder after a detach failure") },
                restoreOrder = {},
                attach = { attached.add(it) }
            )
            fail("Expected native failure")
        } catch (expected: IllegalStateException) {
            assertEquals("detach", expected.message)
        }
        assertTrue(failed)
        assertEquals(groups.toSet(), attached)
    }
}
