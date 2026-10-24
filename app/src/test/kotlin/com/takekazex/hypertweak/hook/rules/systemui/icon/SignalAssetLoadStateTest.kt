package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class SignalAssetLoadStateTest {
    @Test fun retiredSuccessAndFailureCannotOverwriteNewAssetsOrBackoff() {
        for (oldResult in listOf(null, "old")) {
            val generation = AtomicLong()
            val state = SignalAssetLoadState<String>(generation, 5000)
            val old = state.begin(state.advance(), 0)!!
            val nextGeneration = state.advance(SignalSvgStyle.IOS27)
            assertNull(state.current(old.generation))
            assertNull(state.begin(old.generation, 0))
            val next = state.begin(nextGeneration, 0)!!
            assertEquals(SignalSvgStyle.IOS27, next.style)
            assertNull(state.begin(nextGeneration, 0))
            assertFalse(state.complete(old, oldResult, 100000))
            assertTrue(state.complete(next, "new", 1))
            assertFalse(state.complete(old, oldResult, 100000))
            assertEquals("new", state.current(nextGeneration))
            val third = state.advance()
            assertNotNull(state.begin(third, 2))
        }
    }

    @Test fun failedCurrentReadRetriesOnlyAfterBackoffAndDuplicateCompletionIsIgnored() {
        val state = SignalAssetLoadState<String>(AtomicLong(), 5000)
        val g = state.advance()
        val ticket = state.begin(g, 10)!!
        assertTrue(state.complete(ticket, null, 20))
        assertFalse(state.complete(ticket, "duplicate", 21))
        assertNull(state.begin(g, 5019))
        val retry = state.begin(g, 5020)!!
        assertTrue(state.complete(retry, "ready", 5021))
        assertEquals("ready", state.current(g))
        assertNull(state.begin(g, 10000))
    }

    @Test fun retirementRacingWithCompletionAlwaysLeavesTheNewGenerationEmpty() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val generation = AtomicLong()
                val state = SignalAssetLoadState<String>(generation, 5000)
                val old = state.begin(state.advance(), 0)!!
                val start = CountDownLatch(1)
                val complete = executor.submit { start.await(); state.complete(old, "old", 1) }
                val retire = executor.submit<Long> { start.await(); state.advance(SignalSvgStyle.IOS27) }
                start.countDown()
                complete.get()
                val next = retire.get()
                assertNull(state.current(next))
                assertNull(state.current(old.generation))
                assertEquals(SignalSvgStyle.IOS27, state.begin(next, 1)!!.style)
            }
        } finally { executor.shutdownNow() }
    }
}
