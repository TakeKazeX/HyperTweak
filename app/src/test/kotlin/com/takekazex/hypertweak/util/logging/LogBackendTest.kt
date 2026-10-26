package com.takekazex.hypertweak.util.logging

import org.junit.Assert.*
import org.junit.Test

class LogBackendTest {
    private class Sink : AppLogSink {
        val sessions = mutableListOf<String>()
        val records = mutableListOf<Pair<String, List<String>>>()
        override fun rotateSession(token: String) { sessions += token }
        override fun append(process: String, lines: List<String>) { records += process to lines }
    }

    @Test fun earlyAndInjectedProcessesHaveNoLocalPersistenceCapability() {
        val backend = LogBackend()
        assertEquals(4, backend.level())
        backend.rotateSession("early")
        backend.append("systemui", listOf("early"))
        var threshold = 5
        backend.bind({ threshold })
        assertEquals(5, backend.level())
        threshold = 3
        assertEquals(3, backend.level())
        backend.append("systemui", listOf("framework owned"))
    }

    @Test fun rebindingAndRetirementCannotWriteThroughTheOldAppSink() {
        val backend = LogBackend()
        val old = Sink(); val current = Sink()
        backend.bind({ 3 }, old)
        backend.rotateSession("old")
        backend.append("app", listOf("before"))
        backend.bind({ 6 }, current)
        backend.rotateSession("current")
        backend.append("app", listOf("after"))
        backend.retire()
        backend.rotateSession("retired")
        backend.append("app", listOf("retired"))
        assertEquals(listOf("old"), old.sessions)
        assertEquals(listOf("current"), current.sessions)
        assertEquals(listOf("before"), old.records.single().second)
        assertEquals(listOf("after"), current.records.single().second)
        assertEquals(4, backend.level())
        backend.bind({ 5 })
        backend.append("hook", listOf("must not persist"))
        assertEquals(1, current.records.size)
    }
}
