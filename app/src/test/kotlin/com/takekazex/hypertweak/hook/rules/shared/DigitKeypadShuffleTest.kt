package com.takekazex.hypertweak.hook.rules.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Random

class DigitKeypadShuffleTest {
    @Test fun `every shuffle is a full digit permutation that differs from the keypad order`() {
        val random = Random(7)
        repeat(2_000) {
            val digits = DigitKeypadShuffle.shuffledDigits(random)
            assertEquals((0..9).toList(), digits.sorted())
            assertFalse(digits.contentEquals(DigitKeypadShuffle.NATIVE))
        }
    }

    @Test fun `a random source that only yields the keypad order is retried until it changes`() {
        // nextInt(n) = n - 1 swaps every element with itself: the first pass is the identity.
        val identityFirst = object : Random() {
            var calls = 0
            override fun nextInt(bound: Int): Int = if (calls++ < 9) bound - 1 else 0
        }
        assertFalse(DigitKeypadShuffle.shuffledDigits(identityFirst).contentEquals(DigitKeypadShuffle.NATIVE))
    }
}
