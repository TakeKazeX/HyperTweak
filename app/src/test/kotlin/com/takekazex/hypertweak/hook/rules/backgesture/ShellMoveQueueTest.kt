package com.takekazex.hypertweak.hook.rules.backgesture

import com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.ShellMoveQueue
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ShellMoveQueueTest {
    @Test fun burstIsBoundedAndCrossingRetreatPrecedesTerminal() {
        val pending = ArrayDeque<Runnable>(); val events = mutableListOf<String>()
        val queue = ShellMoveQueue(Executor(pending::add)) { x, _, _, crossed, _ -> events += "$x:$crossed" }
        repeat(1000) { queue.submit(it.toFloat(), 0f, it.toFloat(), it == 10, true) }
        queue.submit(2f, 0f, 2f, false, true)
        assertEquals(1, pending.size)
        queue.seal(); pending.add(Runnable { events += "UP" })
        assertFalse(queue.submit(9999f, 0f, 1f, false, true))
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertEquals(listOf("10.0:true", "2.0:false", "UP"), events)
    }
    @Test fun movesArrivingDuringConsumptionQueueBeforeReleaseAndInlineExecutorIsValid() {
        val pending = ArrayDeque<Runnable>(); val values = mutableListOf<Float>()
        lateinit var queue: ShellMoveQueue
        queue = ShellMoveQueue(Executor(pending::add)) { x, _, _, _, _ ->
            values += x
            if (x == 1f) queue.submit(2f, 0f, 2f, false, true)
        }
        queue.submit(1f, 0f, 1f, false, true); pending.removeFirst().run()
        queue.seal(); pending.add(Runnable { values += -1f })
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertEquals(listOf(1f, 2f, -1f), values)
        val direct = mutableListOf<Float>()
        val inline = ShellMoveQueue(Executor(Runnable::run)) { x, _, _, _, _ -> direct += x }
        repeat(20) { inline.submit(it.toFloat(), 0f, 0f, false, false) }
        assertEquals(20, direct.size)
    }
    @Test fun rejectedSchedulingDoesNotLeaveAnUnconsumablePendingMove() {
        var reject = true; val values = mutableListOf<Float>()
        val queue = ShellMoveQueue(Executor { if (reject) throw java.util.concurrent.RejectedExecutionException() else it.run() }) {
            x, _, _, _, _ -> values += x
        }
        assertThrows(java.util.concurrent.RejectedExecutionException::class.java) { queue.submit(1f, 0f, 0f, true, true) }
        reject = false; assertTrue(queue.submit(2f, 0f, 0f, false, true))
        assertEquals(listOf(2f), values)
    }
}
