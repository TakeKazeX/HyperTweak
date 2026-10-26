package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class ViewPropertyRoutesTest {
    @Test fun nativeIntentIsNotPollutedByEarlierRewrites() {
        val routes = ViewPropertyRoutes<String, Int> { _, error -> throw error }
        val seen = mutableListOf<Int>()
        routes.register("hide", { _, requested -> seen += requested; 4 }, null)
        routes.register("mask", { _, requested -> seen += requested; 8 }, null)
        assertEquals(8, routes.before("target", 0))
        assertEquals(listOf(0, 0), seen)
    }

    @Test fun unmatchedRulesPreserveEarlierProposalsAndFailuresAreIsolated() {
        val failures = mutableListOf<String>()
        val routes = ViewPropertyRoutes<String, Float> { owner, _ -> failures += owner }
        routes.register("hide", { view, _ -> if (view == "target") 0f else null }, null)
        routes.register("broken", { _, _ -> error("broken") }, { error("broken observer") })
        var observed = false
        routes.register("observe", null, { observed = true })
        assertEquals(0f, routes.before("target", 1f))
        assertEquals(1f, routes.before("other", 1f))
        routes.after("target")
        assertTrue(observed)
        assertEquals(listOf("broken", "broken", "broken"), failures)
    }

    @Test fun retirementAndResubscriptionDoNotKeepOldRules() {
        val routes = ViewPropertyRoutes<String, Int> { _, error -> throw error }
        var oldCalls = 0
        val old = routes.register("old", { _, _ -> oldCalls++; 4 }, null)
        assertEquals(4, routes.before("target", 0))
        old.close()
        old.close()
        routes.register("next", { _, _ -> 8 }, null)
        assertEquals(8, routes.before("target", 0))
        assertEquals(1, oldCalls)
        routes.clear()
        assertEquals(0, routes.before("target", 0))
    }
    @Test fun unrelatedRuntimeClassesAreNotDispatchedAndRetirementInvalidatesClassification() {
        val routes = ViewPropertyRoutes<Any, Int> { _, error -> throw error }
        var calls = 0
        val subscription = routes.register("strings", { _, _ -> calls++; 4 }, null, String::class.java)
        assertEquals(0, routes.before(Any(), 0))
        repeat(5) { assertEquals(4, routes.before("view", 0)) }
        assertEquals(5, calls)
        subscription.close()
        assertEquals(0, routes.before("view", 0))
        routes.register("all", { _, _ -> 8 }, null)
        assertEquals(8, routes.before(Any(), 0))
    }

}
