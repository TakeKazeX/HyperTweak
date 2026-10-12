package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui

import org.junit.Assert.*
import org.junit.Test

class TransitionAnimatorSetTest {
    @Test fun `multipart children do not count as additional native wrappers`() {
        val primary = Any()
        val auxiliary = Any()
        val otherWindow = Any()
        val wrappers = listOf(arrayOf(primary, auxiliary), arrayOf(otherWindow))
        val leaves = TransitionAnimatorSet.collect(wrappers, 2) { (it as Array<*>) }
        assertEquals(listOf(primary, auxiliary, otherWindow), leaves)
    }

    @Test fun `a missing wrapper rejects the entire snapshot even when leaf counts match`() {
        val remaining = listOf(arrayOf(Any(), Any()))
        assertNull(TransitionAnimatorSet.collect(remaining, 2) { (it as Array<*>) })
    }

    @Test fun `shared leaf identity cannot receive duplicate lifecycle listeners`() {
        val shared = Any()
        val wrappers = listOf(arrayOf(shared), arrayOf(shared))
        assertNull(TransitionAnimatorSet.collect(wrappers, 2) { (it as Array<*>) })
    }

    @Test fun `an unsupported or empty part cannot leave a partially observed animation`() {
        for (bad in listOf<Array<Any?>?>(null, emptyArray(), arrayOf(null))) {
            val wrappers = listOf(arrayOf(Any()), bad)
            assertNull(TransitionAnimatorSet.collect(wrappers, 2) { it as Array<*>? })
        }
    }
}
