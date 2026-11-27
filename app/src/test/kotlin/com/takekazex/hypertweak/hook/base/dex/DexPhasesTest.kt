package com.takekazex.hypertweak.hook.base.dex

import org.junit.Assert.*
import org.junit.Test

class DexPhasesTest {
    private val ms = 1_000_000L

    @Test fun `a nested resolution keeps its own phases out of the outer line`() {
        val lines = ArrayList<String>()
        DexPhases.frame { outer ->
            DexPhases.add("identity", 3 * ms)
            DexPhases.frame { inner -> DexPhases.add("scan", 40 * ms); lines += inner() }
            DexPhases.add("scan", 7 * ms)
            DexPhases.add("scan", 5 * ms)
            lines += outer()
        }
        assertEquals(listOf("scan:40", "identity:3,scan:12"), lines)
    }

    @Test fun `phases outside a resolution are dropped and sub-millisecond phases are not printed`() {
        DexPhases.add("scan", 50 * ms)
        assertEquals("", DexPhases.frame { phases -> DexPhases.add("read", ms / 2); phases() })
    }

    @Test fun `a phase excludes the phases nested inside it`() {
        var elapsedMs = 0L
        val line = DexPhases.frame { phases ->
            val started = System.nanoTime()
            DexPhases.measure("identity") { DexPhases.add("lock", 30 * ms); Thread.sleep(45) }
            elapsedMs = (System.nanoTime() - started) / ms
            phases()
        }
        val values = line.split(',').associate { it.substringBefore(':') to it.substringAfter(':').toLong() }
        assertEquals(30L, values["lock"])
        // identity ran about 45 ms; the 30 ms recorded as a nested lock wait counts only under lock.
        assertTrue("identity=${values["identity"]} elapsed=$elapsedMs", values.getValue("identity") <= elapsedMs - 30 + 1)
    }
}
