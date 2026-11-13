package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemserver

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.Callable
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

class ServerBarFollowLifecycleTest {
    @Test fun reloadWaitsForBothObservedCommitAndNativeRestoreReturn() {
        val lifecycle = ServerBarFollowLifecycle()
        val controller = Any()
        val owner = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        var commit: Runnable? = null
        try {
            lifecycle.begin(controller, owner) {
                commit = it
                entered.countDown()
                check(allowReturn.await(2, TimeUnit.SECONDS))
            }
            assertFalse(lifecycle.reserveReload())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val waiting = FutureTask(Callable { lifecycle.awaitReload(2000) })
            val caller = Thread(waiting, "bar-reload-test")
            caller.start()
            requireNotNull(commit).run()
            assertFalse(lifecycle.awaitReload(0))
            allowReturn.countDown()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
            assertNull(lifecycle.enterMutation(controller))
            lifecycle.cancelReloadReservation()
            lifecycle.begin(controller, Executor { it.run() }) { it.run() }
            requireNotNull(lifecycle.enterMutation(controller)).close()
        } finally {
            allowReturn.countDown()
            owner.shutdownNow()
        }
    }

    @Test fun timeoutOrFailureNeverCountsAsRestoration() {
        val lifecycle = ServerBarFollowLifecycle()
        val controller = Any()
        var commit: Runnable? = null
        lifecycle.begin(controller, Executor { it.run() }) { commit = it }
        assertFalse(lifecycle.reserveReload())
        assertFalse(lifecycle.awaitReload(1))
        assertThrows(IllegalStateException::class.java) { lifecycle.retiredControllers() }
        requireNotNull(commit).run()
        assertTrue(lifecycle.awaitReload(0))

        val failed = ServerBarFollowLifecycle()
        failed.begin(Any(), Executor { it.run() }) { error("native restore rejected") }
        assertFalse(failed.reserveReload())
        assertFalse(failed.awaitReload(1000))
    }

    @Test fun synchronousWritesDrainBeforeRestoreAndBypassIsOwnerLocal() {
        val lifecycle = ServerBarFollowLifecycle()
        val owner = Any()
        val other = Any()
        val queue = ArrayDeque<Runnable>()
        var commit: Runnable? = null
        lifecycle.begin(other, Executor { queue.add(it) }) { }
        lifecycle.retire(other)
        lifecycle.begin(owner, Executor { queue.add(it) }) {
            requireNotNull(lifecycle.enterMutation(owner)).close()
            assertNull(lifecycle.enterMutation(other))
            assertNull(lifecycle.enter(Any(), owner))
            commit = it
        }
        val write = requireNotNull(lifecycle.enterMutation(owner))
        lifecycle.retire(owner)
        assertNull(lifecycle.enterMutation(owner))
        assertEquals(1, queue.size)
        write.close()
        assertEquals(2, queue.size)
        queue.remove().run()
        queue.remove().run()
        assertNull(lifecycle.enterMutation(owner))
        requireNotNull(commit).run()
        assertNull(lifecycle.enterMutation(owner))
    }

    @Test fun earlyCommitDoesNotHideBookkeepingFailure() {
        val lifecycle = ServerBarFollowLifecycle()
        val owner = Any()
        var fail = true
        lifecycle.begin(owner, Executor { it.run() }) {
            it.run()
            assertThrows(IllegalStateException::class.java) { lifecycle.retiredControllers() }
            if (fail) error("native bookkeeping failed after commit")
        }
        lifecycle.retire(owner)
        requireNotNull(lifecycle.failure(owner))
        assertThrows(IllegalStateException::class.java) { lifecycle.retiredControllers() }
        fail = false
        lifecycle.retire(owner)
        assertEquals(owner, lifecycle.retiredControllers().single())
        assertNull(lifecycle.failure(owner))
    }

    @Test fun callbacksCreatedWhileRetiringCannotEnterNextGeneration() {
        val lifecycle = ServerBarFollowLifecycle()
        val owner = Any()
        var commit: Runnable? = null
        lifecycle.begin(owner, Executor { it.run() }) { commit = it }
        val active = Any()
        lifecycle.created(active, owner)
        val permit = requireNotNull(lifecycle.enter(active, owner))
        lifecycle.retire(owner)
        val queuedByOldCallback = Any()
        lifecycle.created(queuedByOldCallback, owner)
        permit.close()
        requireNotNull(commit).run()
        lifecycle.begin(owner, Executor { it.run() }) { it.run() }
        assertNull(lifecycle.enter(queuedByOldCallback, owner))
        val current = Any()
        lifecycle.created(current, owner)
        requireNotNull(lifecycle.enter(current, owner)).close()
    }

    @Test fun retirementWaitsForEnteredCallbackAndSurfaceCommit() {
        val lifecycle = ServerBarFollowLifecycle()
        val owner = Any()
        val callback = Any()
        val queued = ArrayDeque<Runnable>()
        var committed: Runnable? = null
        lifecycle.begin(owner, Executor { queued.add(it) }) { committed = it }
        lifecycle.created(callback, owner)
        val entered = requireNotNull(lifecycle.enter(callback, owner))
        assertFalse(lifecycle.reserveReload())
        assertNull(lifecycle.enter(callback, owner))
        assertTrue(queued.isEmpty())
        entered.close()
        entered.close()
        assertEquals(1, queued.size)
        queued.remove().run()
        assertFalse(lifecycle.reserveReload())
        requireNotNull(committed).run()
        assertTrue(lifecycle.reserveReload())
    }

    @Test fun listenerCannotBorrowReplacementLifetime() {
        val lifecycle = ServerBarFollowLifecycle()
        val owner = Any()
        val old = Any()
        lifecycle.created(old, owner)
        lifecycle.begin(owner, Executor { it.run() }) { it.run() }
        assertNull(lifecycle.enter(old, owner))
        val first = Any()
        lifecycle.created(first, owner)
        requireNotNull(lifecycle.enter(first, owner)).close()
        lifecycle.retire(owner)
        lifecycle.begin(owner, Executor { it.run() }) { it.run() }
        assertNull(lifecycle.enter(first, owner))
        val second = Any()
        lifecycle.created(second, owner)
        requireNotNull(lifecycle.enter(second, owner)).close()
    }

    @Test fun failedRestoreCannotBeCompletedByItsLateCommit() {
        val lifecycle = ServerBarFollowLifecycle()
        val owner = Any()
        val commits = mutableListOf<Runnable>()
        lifecycle.begin(owner, Executor { it.run() }) {
            commits.add(it)
            if (commits.size == 1) error("apply failed")
        }
        lifecycle.retire(owner)
        requireNotNull(lifecycle.failure(owner))
        lifecycle.retire(owner)
        commits[0].run()
        assertThrows(IllegalStateException::class.java) { lifecycle.retiredControllers() }
        commits[1].run()
        assertEquals(owner, lifecycle.retiredControllers().single())
    }

    @Test fun reloadRetainsDenialIncludingReplacedNativeController() {
        val oldRuntime = ServerBarFollowLifecycle()
        val owner = Any()
        val queuedStart = Any()
        oldRuntime.begin(owner, Executor { it.run() }) { it.run() }
        oldRuntime.created(queuedStart, owner)
        assertTrue(oldRuntime.reserveReload())
        val replacement = ServerBarFollowLifecycle()
        replacement.restoreRetiredControllers(oldRuntime.retiredControllers())
        replacement.restoreRetiredCallbacks(oldRuntime.retiredCallbacks())
        assertNull(replacement.enter(queuedStart, owner))
        assertNull(replacement.enter(queuedStart, Any()))
        requireNotNull(replacement.enter(Any(), Any())).close()
    }
}
