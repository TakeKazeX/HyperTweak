package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EqualsDistinctConcatTest {
    @Test fun duplicatesAcrossBothListsCountOnceInFirstPosition() {
        assertTrue(equalsDistinctConcat(listOf("wifi", "mobile", "airplane"), listOf("wifi", "mobile"), listOf("mobile", "airplane")))
        assertFalse(equalsDistinctConcat(listOf("wifi", "airplane", "mobile"), listOf("wifi", "mobile"), listOf("mobile", "airplane")))
    }

    @Test fun lengthAndForeignEntriesMatter() {
        assertFalse(equalsDistinctConcat(listOf("wifi"), listOf("wifi", "mobile"), emptyList()))
        assertFalse(equalsDistinctConcat(listOf("wifi", "mobile", "nfc"), listOf("wifi", "mobile"), emptyList()))
        assertFalse(equalsDistinctConcat(listOf("wifi", null), listOf("wifi"), emptyList()))
        assertTrue(equalsDistinctConcat(emptyList<String>(), emptyList(), emptyList()))
    }

    @Test fun agreesWithTheAllocatingExpression() {
        val pool = listOf("wifi", "mobile", "airplane", "nfc", "alarm_clock", "stacked_mobile")
        val random = Random(17)
        fun list() = List(random.nextInt(0, 7)) { pool[random.nextInt(pool.size)] }
        repeat(5_000) {
            val first = list()
            val second = list()
            val expected = (first + second).distinct()
            val actual = if (random.nextBoolean()) expected else list()
            assertEquals("$actual vs $first + $second", actual == expected, equalsDistinctConcat(actual, first, second))
        }
    }
}
