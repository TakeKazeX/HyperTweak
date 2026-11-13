package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemserver

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ServerBarGestureScopeTest {
    @Test fun repeatedSnapshotDeliveryKeepsOnlyItsLiveControllerAndReleasesEachScope() {
        val policy = ServerBarGestureScope()
        val controller = Any()
        repeat(4) {
            policy.enter(controller, 6, true).use {
                assertTrue(policy.keepsLiveStatus(controller))
                assertFalse(policy.keepsLiveStatus(Any()))
            }
            assertFalse(policy.keepsLiveStatus(controller))
        }
    }

    @Test fun nativeFinishCancelAndUnknownStatesDoNotBorrowSnapshotAdmission() {
        val policy = ServerBarGestureScope()
        val controller = Any()
        policy.enter(controller, 6, true).use {
            for (state in listOf(-1, 0, 1, 3, 7, 9, 10, 12, 14, 15)) {
                policy.enter(controller, state, true).use {
                    assertFalse("state=$state", policy.keepsLiveStatus(controller))
                }
                assertTrue(policy.keepsLiveStatus(controller))
            }
        }
    }

    @Test fun disabledRetiredOrUnprovenOwnersRetainTheNativeSnapshotPath() {
        val policy = ServerBarGestureScope()
        val controller = Any()
        policy.enter(controller, 6, false).use { assertFalse(policy.keepsLiveStatus(controller)) }
        policy.enter(null, 6, true).use { assertFalse(policy.keepsLiveStatus(null)) }
        policy.enter(controller, 6, true).use {
            policy.enter(controller, 6, false).use { assertFalse(policy.keepsLiveStatus(controller)) }
            assertTrue(policy.keepsLiveStatus(controller))
        }
    }

    @Test fun nestedControllersAndThrowingCallbacksRestoreTheExactPreviousOwner() {
        val policy = ServerBarGestureScope()
        val first = Any()
        val second = Any()
        policy.enter(first, 6, true).use {
            try {
                policy.enter(second, 6, true).use {
                    assertFalse(policy.keepsLiveStatus(first))
                    assertTrue(policy.keepsLiveStatus(second))
                    throw IllegalStateException("native callback failed")
                }
            } catch (_: IllegalStateException) { }
            assertTrue(policy.keepsLiveStatus(first))
            assertFalse(policy.keepsLiveStatus(second))
        }
        assertFalse(policy.keepsLiveStatus(first))
    }

    @Test fun concurrentNativeCallbacksCannotBorrowAnotherThreadsOwner() {
        val policy = ServerBarGestureScope()
        val controller = Any()
        val borrowed = AtomicBoolean(true)
        policy.enter(controller, 6, true).use {
            Thread { borrowed.set(policy.keepsLiveStatus(controller)) }.apply { start(); join() }
            assertFalse(borrowed.get())
            assertTrue(policy.keepsLiveStatus(controller))
        }
    }

    @Test fun recentsInitializationOnlyDefersItsEarlyHomeTarget() {
        val policy = ServerBarGestureScope()
        val controller = Any()
        policy.enterRecentsStart(controller, true).use {
            assertTrue(policy.keepsAppDuringStart(controller, 1f, 0f))
            assertFalse(policy.keepsAppDuringStart(Any(), 1f, 0f))
            assertFalse(policy.keepsAppDuringStart(controller, 0f, 1f))
            assertFalse(policy.keepsAppDuringStart(controller, 0f, 0f))
            assertFalse(policy.keepsAppDuringStart(controller, 1f, 1f))
            assertFalse(policy.keepsLiveStatus(controller))
            policy.enter(controller, 6, true).use {
                assertTrue(policy.keepsLiveStatus(controller))
                assertFalse(policy.keepsAppDuringStart(controller, 1f, 0f))
            }
            assertTrue(policy.keepsAppDuringStart(controller, 1f, 0f))
        }
        assertFalse(policy.keepsAppDuringStart(controller, 1f, 0f))
    }

    @Test fun ordinaryLaunchAndUnprovenRecentsInitializationKeepNativeTargets() {
        val policy = ServerBarGestureScope()
        val controller = Any()
        policy.enterRecentsStart(controller, false).use {
            assertFalse(policy.keepsAppDuringStart(controller, 1f, 0f))
        }
        policy.enterRecentsStart(null, true).use {
            assertFalse(policy.keepsAppDuringStart(null, 1f, 0f))
        }
    }

    @Test fun aLateInitializationCannotOverrideAnAlreadyDeliveredTerminalState() {
        for (state in listOf(-1, 3, 6, 12, 14)) assertTrue(ServerBarGestureScope.isPendingStartState(state))
        for (state in listOf(0, 1, 2, 5, 7, 9, 10, 15, 99))
            assertFalse("terminal/unknown state=$state", ServerBarGestureScope.isPendingStartState(state))
    }
}
