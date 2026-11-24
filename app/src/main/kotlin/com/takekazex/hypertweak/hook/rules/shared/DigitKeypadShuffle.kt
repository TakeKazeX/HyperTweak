package com.takekazex.hypertweak.hook.rules.shared

import android.view.View
import android.view.ViewGroup
import java.security.SecureRandom
import java.util.Random

/**
 * Moves the ten digit keys of a host PIN pad between the pad's own digit positions.
 *
 * Slots are numbered like a phone keypad: slots 0..8 are the three digit rows left to right,
 * slot 9 is the middle of the bottom row. A slot is described by the key that occupies it now
 * (its parent, its index there and its layout params), so a pad that is already shuffled, even by
 * an older module generation, is restored by arranging the digits in [NATIVE]. Keys keep their
 * listeners and digit values; only their parent, index and layout params change. Non-digit keys
 * sharing a row (delete, emergency, verify) keep their indices because every digit is removed
 * first and re-inserted in ascending index order.
 */
internal object DigitKeypadShuffle {
    const val SLOTS = 10

    /** Digit per slot on a stock keypad: 1..9, then 0. */
    val NATIVE: IntArray = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

    private val secureRandom by lazy { SecureRandom() }

    /** A uniformly random digit order other than [NATIVE]. */
    fun shuffledDigits(random: Random = secureRandom): IntArray {
        val digits = NATIVE.copyOf()
        do {
            for (i in digits.lastIndex downTo 1) {
                val j = random.nextInt(i + 1)
                val swap = digits[i]; digits[i] = digits[j]; digits[j] = swap
            }
        } while (digits.contentEquals(NATIVE))
        return digits
    }

    /**
     * Places `keysByDigit[digits[slot]]` into each slot currently held by `occupants[slot]`.
     * Returns the new occupants by slot, or null (nothing moved) when the pad does not have the
     * expected shape: ten distinct keys, each the occupant of exactly one slot, all attached to
     * a parent [ViewGroup].
     */
    fun arrange(occupants: List<View>, keysByDigit: List<View>, digits: IntArray): List<View>? {
        if (occupants.size != SLOTS || keysByDigit.size != SLOTS || digits.size != SLOTS) return null
        if (digits.sorted() != (0 until SLOTS).toList()) return null
        if (occupants.toSet().size != SLOTS || occupants.toSet() != keysByDigit.toSet()) return null
        class Slot(val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)
        val slots = occupants.map { key ->
            val parent = key.parent as? ViewGroup ?: return null
            val index = parent.indexOfChild(key).takeIf { it >= 0 } ?: return null
            Slot(parent, index, key.layoutParams ?: return null)
        }
        val next = digits.map { keysByDigit[it] }
        if (next == occupants) return next
        occupants.forEach { key -> (key.parent as ViewGroup).removeView(key) }
        slots.indices.sortedBy { slots[it].index }.forEach { slot ->
            slots[slot].parent.addView(next[slot], slots[slot].index, slots[slot].params)
        }
        return next
    }
}
