package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction
import com.takekazex.hypertweak.hook.rules.systemui.island.session.CallbackContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackContextTest {
    @Test fun `nested scopes restore the surrounding frame`() {
        val context = CallbackContext()
        val session = IslandSession(Any())
        val outer = SchedulingTransaction(session, emptyList(), null)
        val inner = SchedulingTransaction(session, emptyList(), "inner")
        val view = Any()
        context.withTransaction(outer) {
            assertSame(outer, context.transaction)
            context.withMeasuring(view) {
                assertSame(outer, context.transaction)
                assertSame(view, context.measuringView)
                context.withAnimating(view) {
                    assertSame(view, context.animatingView)
                    context.withRendering(view) {
                        assertSame(view, context.renderingView)
                        context.withTransaction(inner) {
                            assertSame(inner, context.transaction)
                            assertSame(view, context.measuringView)
                        }
                    }
                }
            }
            assertSame(outer, context.transaction)
            assertNull(context.measuringView)
            assertNull(context.animatingView)
            assertNull(context.renderingView)
        }
        assertNull(context.transaction)
    }

    @Test fun `clearing inside a scope does not leak into the outer frame`() {
        val context = CallbackContext()
        val session = IslandSession(Any())
        val transaction = SchedulingTransaction(session, emptyList(), null)
        context.withTransaction(transaction) {
            context.withMeasuring(Any()) {
                context.setTransaction(null)
                assertNull(context.transaction)
            }
            assertSame(transaction, context.transaction)
        }
        assertNull(context.transaction)
    }

    @Test fun `frames are thread local and idle frames are removed`() {
        val context = CallbackContext()
        val transaction = SchedulingTransaction(IslandSession(Any()), emptyList(), null)
        context.setTransaction(transaction)
        var seen: SchedulingTransaction? = transaction
        val thread = Thread { seen = context.transaction }
        thread.start()
        thread.join()
        assertNull(seen)
        context.setTransaction(null)
        assertNull(context.transaction)
        context.clearIfIdle()
    }

    @Test fun `exception still restores the surrounding frame`() {
        val context = CallbackContext()
        val transaction = SchedulingTransaction(IslandSession(Any()), emptyList(), null)
        context.withTransaction(transaction) {
            val failure = runCatching<Unit> {
                context.withMeasuring(Any()) {
                    throw IllegalStateException("native callback failed")
                }
            }
            assertTrue(failure.isFailure)
            assertSame(transaction, context.transaction)
            assertNull(context.measuringView)
        }
        assertNull(context.transaction)
    }
}
