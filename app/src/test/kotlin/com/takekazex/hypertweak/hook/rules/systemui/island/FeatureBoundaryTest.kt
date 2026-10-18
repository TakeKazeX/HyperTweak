package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction
import com.takekazex.hypertweak.hook.rules.systemui.island.session.CallbackContext
import com.takekazex.hypertweak.hook.rules.systemui.island.session.FeatureBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureBoundaryTest {
    private val logs = mutableListOf<String>()
    private val restored = mutableListOf<IslandSession>()

    private fun boundary(context: CallbackContext) = FeatureBoundary(context) { _, message, error, soft ->
        logs += "${if (soft) "soft" else "core"}:$message:${error?.message}"
    }.also { it.restorer = { session -> restored += session } }

    @Test fun `core failure marks the session failed and restores native scheduling`() {
        val context = CallbackContext()
        val session = IslandSession(Any())
        val transaction = SchedulingTransaction(session, emptyList(), null)
        val result = context.withTransaction(transaction) {
            boundary(context).core(session, IslandFeature.MEASUREMENT, "measure three islands") {
                error("host contract changed")
            }
        }
        assertNull(result)
        assertTrue(session.health.failed)
        assertTrue(session.health.isDegraded(IslandFeature.MEASUREMENT))
        assertTrue(transaction.settled)
        assertEquals(listOf(session), restored)
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("core:measure three islands failed"))
    }

    @Test fun `spectrum failure only degrades its feature and keeps native presentation`() {
        val context = CallbackContext()
        val session = IslandSession(Any())
        val transaction = SchedulingTransaction(session, emptyList(), null)
        val result = context.withTransaction(transaction) {
            boundary(context).soft(session, IslandFeature.SPECTRUM, "evaluate small spectrum") {
                error("template shape changed")
            }
        }
        assertNull(result)
        assertFalse(session.health.failed)
        assertTrue(session.health.isDegraded(IslandFeature.SPECTRUM))
        assertFalse(transaction.settled)
        assertTrue(restored.isEmpty())
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("soft:evaluate small spectrum degraded"))
    }

    @Test fun `lyric guard failure is soft even when wrapped as core by mistake`() {
        val context = CallbackContext()
        val session = IslandSession(Any())
        FeatureBoundary(context) { _, _, _, _ -> }.core(session, IslandFeature.LYRIC_GUARD, "sync guard") {
            error("guard view detached")
        }
        assertFalse(session.health.failed)
        assertTrue(session.health.isDegraded(IslandFeature.LYRIC_GUARD))
    }

    @Test fun `successful blocks pass through without degradation`() {
        val context = CallbackContext()
        val session = IslandSession(Any())
        val result = boundary(context).core(session, IslandFeature.MEASUREMENT, "measure") { 42 }
        assertEquals(42, result)
        assertFalse(session.health.failed)
        assertTrue(session.health.failedFeatures.isEmpty())
        assertTrue(restored.isEmpty())
        assertTrue(logs.isEmpty())
    }

    @Test fun `a throwing restorer is logged instead of escaping`() {
        val context = CallbackContext()
        val session = IslandSession(Any())
        val strict = FeatureBoundary(context) { _, message, error, _ ->
            logs += "$message:${error?.message}"
        }.also { it.restorer = { error("restore failed too") } }
        val result = strict.core(session, IslandFeature.TOUCH, "route touch") { error("boom") }
        assertNull(result)
        assertTrue(session.health.failed)
        assertEquals(2, logs.size)
        assertTrue(logs.last().startsWith("native restoration failed"))
    }
}
