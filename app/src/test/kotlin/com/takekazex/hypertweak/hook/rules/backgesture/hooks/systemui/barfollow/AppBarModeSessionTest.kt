package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class AppBarModeSessionTest {
    @Test
    fun ordinaryDistinctFlowSuppliesWakefulnessWithoutSynchronousValueAccess() = runBlocking {
        val applied = mutableListOf<Pair<Any, Boolean>>()
        val failures = mutableListOf<RuntimeException>()
        val session = AppBarModeSession({ mode, awake -> applied += mode to awake }, failures::add)
        val mode = session.guard(session::mode)
        val awake = session.guard(session::awake)
        val wakefulness = flowOf(false, false, true).distinctUntilChanged()
        assertFalse(wakefulness is StateFlow<*>)
        assertFalse(wakefulness.javaClass.methods.any { it.name == "getValue" })

        mode.accept("opaque")
        assertTrue(applied.isEmpty())
        wakefulness.collect { awake.accept(it) }

        assertEquals(listOf<Pair<Any, Boolean>>("opaque" to false, "opaque" to true), applied)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun wakefulnessMayArriveBeforeModeWithoutInventingAnInitialMode() {
        val applied = mutableListOf<Pair<Any, Boolean>>()
        val session = AppBarModeSession({ mode, awake -> applied += mode to awake }) { throw it }
        session.guard(session::awake).accept(false)
        assertTrue(applied.isEmpty())

        session.guard(session::mode).accept("transparent")
        assertEquals(listOf<Pair<Any, Boolean>>("transparent" to false), applied)
    }

    @Test
    fun onlyLatestPendingModeUsesTheFirstRealWakefulnessValue() {
        val applied = mutableListOf<Pair<Any, Boolean>>()
        val session = AppBarModeSession({ mode, awake -> applied += mode to awake }) { throw it }
        val mode = session.guard(session::mode)
        mode.accept("transparent")
        mode.accept("opaque")
        assertTrue(applied.isEmpty())

        session.guard(session::awake).accept(true)
        assertEquals(listOf<Pair<Any, Boolean>>("opaque" to true), applied)
    }

    @Test
    fun laterModeUsesTheLatestCollectedWakefulness() {
        val applied = mutableListOf<Pair<Any, Boolean>>()
        val session = AppBarModeSession({ mode, awake -> applied += mode to awake }) { throw it }
        val awake = session.guard(session::awake)
        val mode = session.guard(session::mode)
        awake.accept(true)
        mode.accept("transparent")
        awake.accept(false)
        mode.accept("opaque")

        assertEquals("opaque" to false, applied.last())
    }

    @Test
    fun reflectionFailureDoesNotEscapeTheFlowOrKeepOtherConsumersAlive() = runBlocking {
        val original = IllegalStateException("App background mode failed", NoSuchMethodException("getValue"))
        val failures = mutableListOf<RuntimeException>()
        var applications = 0
        var otherUpdates = 0
        val session = AppBarModeSession({ _, _ -> applications++; throw original }, failures::add)
        val awake = session.guard(session::awake)
        val mode = session.guard(session::mode)
        val other = session.guard { otherUpdates++ }
        awake.accept(true)

        flowOf("opaque", "transparent").collect { mode.accept(it) }
        awake.accept(false)
        other.accept(true)

        assertEquals(1, applications)
        assertEquals(0, otherUpdates)
        assertEquals(1, failures.size)
        assertSame(original, failures.single())
    }

    @Test
    fun invalidFlowValueRetiresTheSessionInsteadOfUsingAConstantFallback() {
        val failures = mutableListOf<RuntimeException>()
        var applications = 0
        val session = AppBarModeSession({ _, _ -> applications++ }, failures::add)
        session.guard(session::mode).accept("opaque")
        val awake = session.guard(session::awake)
        awake.accept("wrong type")
        awake.accept(true)

        assertEquals(0, applications)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is ClassCastException)
    }

    @Test
    fun closedOwnerIgnoresLateCallbacksAndCannotApplyItsCachedMode() {
        val failures = mutableListOf<RuntimeException>()
        var applications = 0
        val session = AppBarModeSession({ _, _ -> applications++ }, failures::add)
        val mode = session.guard(session::mode)
        val awake = session.guard(session::awake)
        mode.accept("opaque")
        session.close()

        awake.accept(true)
        mode.accept("transparent")
        awake.accept("stale malformed value")
        assertEquals(0, applications)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun failingRetirementCallbackCannotCrashTheNativeCollector() {
        val original = IllegalStateException("consumer failed")
        val reporting = IllegalStateException("retirement callback failed")
        val session = AppBarModeSession({ _, _ -> }, { throw reporting })
        val callback = session.guard { throw original }

        callback.accept(true)
        callback.accept(false)

        assertEquals(listOf(reporting), original.suppressed.toList())
    }
}
