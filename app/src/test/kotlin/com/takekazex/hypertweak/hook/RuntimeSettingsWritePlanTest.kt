package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test

class RuntimeSettingsWritePlanTest {
    private val first = "icon_duo_enabled"
    private val second = "icon_stacked_signal_style"
    private fun applied(base: Map<String, Any>, plan: RuntimeSettingsWritePlan) =
        base.filterKeys { it !in plan.remove } + plan.put

    @Test fun `one edit sends only the changed key even with a large registered subset`() {
        val before = (0 until 180).associate { "icon_tuner_slot_test_$it" to true }
        val after = before + ("icon_tuner_slot_test_7" to false)
        val plan = RuntimeSettingsWritePlan.create(before, before, after)
        assertEquals(mapOf("icon_tuner_slot_test_7" to false), plan.put)
        assertTrue(plan.remove.isEmpty())
        assertEquals(after, applied(before, plan))
        val unchanged = RuntimeSettingsWritePlan.create(after, after, after)
        assertTrue(unchanged.put.isEmpty())
        assertTrue(unchanged.remove.isEmpty())
    }

    @Test fun `next commit repairs a rejected shadow without publishing rejected values`() {
        val acknowledged = mapOf<String, Any>(first to false, second to 0)
        val rejected = acknowledged + (first to true)
        val desired = acknowledged + (second to 1)
        val plan = RuntimeSettingsWritePlan.create(rejected, acknowledged, desired)
        assertEquals(desired, plan.put)
        // Covers definite rejection and an IPC failure after the daemon accepted the call.
        assertEquals(desired, applied(acknowledged, plan))
        assertEquals(desired, applied(rejected, plan))
    }

    @Test fun `retry sends a changed value even when the editor shadow already matches it`() {
        val acknowledged = mapOf<String, Any>(first to false)
        val desired = mapOf<String, Any>(first to true)
        val plan = RuntimeSettingsWritePlan.create(desired, acknowledged, desired)
        assertEquals(desired, plan.put)
        assertEquals(desired, applied(acknowledged, plan))
    }

    @Test fun `failed removal and newly introduced rejected keys cannot resurrect`() {
        val acknowledged = mapOf<String, Any>(first to true)
        val rejectedShadow = mapOf<String, Any>(second to 1)
        val plan = RuntimeSettingsWritePlan.create(rejectedShadow, acknowledged, emptyMap())
        assertEquals(setOf(first, second), plan.remove)
        assertTrue(plan.put.isEmpty())
        assertTrue(applied(acknowledged, plan).isEmpty())
        assertTrue(applied(rejectedShadow, plan).isEmpty())
    }

    @Test fun `reset restore and clear rollback repopulate equal values completely`() {
        val acknowledged = mapOf<String, Any>(first to true, second to 1)
        val replacement = mapOf<String, Any>(first to true)
        val plan = RuntimeSettingsWritePlan.create(acknowledged, acknowledged, replacement, complete = true)
        assertEquals(setOf(second), plan.remove)
        assertEquals(replacement, plan.put)
        assertEquals(replacement, applied(emptyMap(), plan))
        val rollback = RuntimeSettingsWritePlan.create(replacement, acknowledged, acknowledged, complete = true)
        assertEquals(acknowledged, applied(emptyMap(), rollback))
    }

    @Test fun `normalization repairs malformed shadows and excludes app diagnostics`() {
        val acknowledged = mapOf<String, Any>(first to false)
        val shadow = mapOf(first to "false", "debug_log_p_app" to "huge record")
        val plan = RuntimeSettingsWritePlan.create(shadow, acknowledged, acknowledged)
        assertEquals(acknowledged, plan.put)
        assertTrue(plan.remove.isEmpty())
    }
}
