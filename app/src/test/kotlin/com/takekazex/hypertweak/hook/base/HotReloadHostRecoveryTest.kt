package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class HotReloadHostRecoveryTest {
    @Test fun `first upgrade recovers new hosts even without a previous feature payload`() {
        val events = mutableListOf<String>()
        HotReloadHostRecovery.run(listOf("existing", "new"), mapOf("existing" to 7), { it },
            { hook, state -> events += "restore:$hook:$state" },
            { events += "recover:$it" }, { _, error -> throw error })
        assertEquals(listOf("restore:existing:7", "recover:existing", "recover:new"), events)
    }

    @Test fun `newer upgrade restores all peers before any recovery and preserves explicit null`() {
        val events = mutableListOf<String>()
        HotReloadHostRecovery.run(listOf("bar", "peer"), mapOf("bar" to true, "peer" to null), { it },
            { hook, state -> events += "restore:$hook:$state" },
            { events += "recover:$it" }, { _, error -> throw error })
        assertEquals(listOf("restore:bar:true", "restore:peer:null", "recover:bar", "recover:peer"), events)
    }

    @Test fun `one broken restore or recovery cannot prevent another host from recovering`() {
        val recovered = mutableListOf<String>()
        val failures = mutableListOf<String>()
        HotReloadHostRecovery.run(listOf("badState", "badHost", "bar"), mapOf("badState" to 1), { it },
            { _, _ -> error("invalid old payload") },
            { if (it == "badHost") error("host unavailable") else recovered += it },
            { hook, _ -> failures += hook })
        assertEquals(listOf("badState", "badHost"), failures)
        assertEquals(listOf("badState", "bar"), recovered)
    }
}
