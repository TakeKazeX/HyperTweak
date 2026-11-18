package com.takekazex.hypertweak.hook.base

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationInputsTest {
    @Test fun aRegistrationClosedBeforeItsDeferredAttachNeverRegisters() {
        val inputs = GenerationInputs()
        val pending = inputs.open()
        inputs.releaseAll()
        var registered = 0
        assertFalse(pending.attach({ registered++ }) { error("never registered") })
        assertEquals(0, registered)
    }

    @Test fun retirementUnregistersOnceAndKeepsGoingAfterAFailure() {
        val inputs = GenerationInputs()
        var released = 0
        val first = inputs.open().apply { attach({ "a" }) { error("host refused") } }
        val second = inputs.open().apply { attach({ "b" }) { released++ } }
        val failure = runCatching { inputs.releaseAll() }.exceptionOrNull()
        assertEquals("host refused", failure?.message)
        assertEquals(1, released)
        first.close(); second.close()
        inputs.releaseAll()
        assertEquals(1, released)
    }

    /** A retiring generation's hook callbacks still run until its handles are replaced. */
    @Test fun aRetiredOwnerRefusesLateRegistrationsUntilReactivated() {
        val inputs = GenerationInputs()
        inputs.releaseAll()
        var registered = 0
        val late = inputs.open()
        assertTrue(late.isClosed)
        assertFalse(late.attach({ registered++ }) { error("never registered") })
        assertEquals(0, registered)

        inputs.activate()
        val reinstalled = inputs.open()
        assertTrue(reinstalled.attach({ registered++ }) { registered-- })
        inputs.releaseAll()
        assertEquals(0, registered)
    }

    @Test fun workersAndScopesAreUsableAgainAfterRetirement() {
        val inputs = GenerationInputs()
        val worker = inputs.reusable(GenerationWorker("test-worker"))
        val scope = inputs.reusable(GenerationScope(Dispatchers.Default))
        val retiredJob = scope.coroutineContext[Job]!!
        inputs.releaseAll()
        assertTrue(retiredJob.isCancelled)

        val ran = CountDownLatch(2)
        assertTrue(worker.execute { ran.countDown() })
        runBlocking { scope.launch { ran.countDown() }.join() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        inputs.releaseAll()
    }
}
