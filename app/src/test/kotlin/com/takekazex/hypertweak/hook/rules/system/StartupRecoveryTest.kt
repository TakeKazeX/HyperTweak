package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.*
import org.junit.Test

class StartupRecoveryTest {
    private class Scheduler : RecoveryScheduler {
        val jobs = mutableListOf<Runnable>()
        var accept = true
        override fun post(task: Runnable, delay: Long): Boolean { if (accept) jobs += task; return accept }
        override fun remove(task: Runnable) { jobs.remove(task) }
        fun tick() { jobs.removeAt(0).run() }
    }
    @Test fun `late service readiness after exhausted polling is recovered by a lifecycle event`() {
        val scheduler = Scheduler(); var ready = false; var runs = 0; var success = 0
        val retry = SystemServerStartupRetry("test", { runs++; ready }, maxAttempts = 2,
            scheduler = scheduler, report = { _, _ -> }, recovered = { success++ })
        try {
            retry.schedule(); scheduler.tick(); scheduler.tick()
            assertEquals(2, runs); assertTrue(scheduler.jobs.isEmpty()); assertEquals(0, success)
            ready = true; SystemServerStartupRetry.onLifecycleEvent(); scheduler.tick()
            assertEquals(3, runs); assertEquals(1, success)
            SystemServerStartupRetry.onLifecycleEvent(); assertTrue(scheduler.jobs.isEmpty())
        } finally { retry.cancel() }
    }
    @Test fun `cancelled generation cannot write settings or report recovery`() {
        val scheduler = Scheduler(); var runs = 0
        val retry = SystemServerStartupRetry("test", { runs++; true }, scheduler = scheduler,
            report = { _, _ -> }, recovered = { fail("stale recovery") })
        retry.schedule(); val old = scheduler.jobs.single(); retry.cancel()
        old.run(); SystemServerStartupRetry.onLifecycleEvent()
        assertEquals(0, runs); assertTrue(scheduler.jobs.isEmpty())
    }
    @Test fun `unexpected errors are recorded and a later successful attempt still recovers`() {
        val scheduler = Scheduler(); val error = IllegalStateException("binder died"); var first = true; var seen: Throwable? = null
        val retry = SystemServerStartupRetry("test", { if (first) { first = false; throw error }; true },
            scheduler = scheduler, report = { _, t -> if (t != null) seen = t }, recovered = {})
        try { retry.schedule(); scheduler.tick(); assertSame(error, seen); scheduler.tick(); assertTrue(scheduler.jobs.isEmpty()) }
        finally { retry.cancel() }
    }
    @Test fun `a rejected handler post stays recoverable without busy polling`() {
        val scheduler = Scheduler().apply { accept = false }; var success = 0
        val retry = SystemServerStartupRetry("test", { true }, scheduler = scheduler,
            report = { _, _ -> }, recovered = { success++ })
        try {
            retry.schedule(); assertTrue(scheduler.jobs.isEmpty())
            scheduler.accept = true; SystemServerStartupRetry.onLifecycleEvent(); scheduler.tick()
            assertEquals(1, success)
        } finally { retry.cancel() }
    }
}
