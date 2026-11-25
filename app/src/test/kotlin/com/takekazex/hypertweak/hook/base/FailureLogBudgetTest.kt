package com.takekazex.hypertweak.hook.base

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureLogBudgetTest {
    @Test fun firstFailuresAreFullThenOnlyPowersOfTenAreSummarized() {
        val logged = (1L..100_000L).filter { FailureLogBudget.entry(it) != FailureLogBudget.Entry.NONE }
        assertEquals(listOf(1L, 2L, 3L, 10L, 100L, 1_000L, 10_000L, 100_000L), logged)
        assertEquals(FailureLogBudget.Entry.FULL, FailureLogBudget.entry(3))
        assertEquals(FailureLogBudget.Entry.SUMMARY, FailureLogBudget.entry(10))
    }
}
